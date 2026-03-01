package com.rocketbunny.swiftmapper.migration;

import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class MigrationRunner {
    private final DataSource dataSource;
    private final SwiftLogger log = SwiftLogger.getLogger(MigrationRunner.class);
    private final String migrationLocation;
    private final boolean checksumVerification;
    private final int maxRetries;
    private final long retryDelayMs;

    public MigrationRunner(DataSource dataSource, String migrationLocation) {
        this(dataSource, migrationLocation, true, 3, 1000);
    }

    public MigrationRunner(DataSource dataSource, String migrationLocation,
                           boolean checksumVerification, int maxRetries, long retryDelayMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
        this.migrationLocation = Objects.requireNonNull(migrationLocation, "Migration location cannot be null");
        this.checksumVerification = checksumVerification;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public MigrationResult runMigrations() throws SQLException {
        log.info("Running migrations from classpath:{}", migrationLocation);

        List<String> migrationFiles = loadMigrationFiles();
        if (migrationFiles.isEmpty()) {
            log.info("No migration files found in {}", migrationLocation);
            return new MigrationResult(0, 0, Collections.emptyList());
        }

        List<String> appliedMigrations = new ArrayList<>();
        List<String> failedMigrations = new ArrayList<>();
        int skippedCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();

            try {
                connection.setAutoCommit(false);
                createMigrationTable(connection);

                MigrationLock lock = acquireLock(connection);
                if (lock == null) {
                    throw new MigrationException("Could not acquire migration lock. Another process may be running migrations.");
                }

                try {
                    for (String file : migrationFiles) {
                        MigrationStatus status = processMigration(connection, file);

                        switch (status) {
                            case APPLIED -> appliedMigrations.add(file);
                            case SKIPPED -> skippedCount++;
                            case FAILED -> failedMigrations.add(file);
                        }

                        if (status == MigrationStatus.FAILED && !failedMigrations.isEmpty()) {
                            break;
                        }
                    }

                    if (failedMigrations.isEmpty()) {
                        connection.commit();
                        log.info("All migrations completed successfully. Applied: {}, Skipped: {}",
                                appliedMigrations.size(), skippedCount);
                    } else {
                        connection.rollback();
                        log.error("Migrations failed. Rolled back. Failed: {}", failedMigrations);
                        throw new MigrationException("Migrations failed: " + failedMigrations);
                    }

                } finally {
                    releaseLock(connection, lock);
                }

            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    log.error("Failed to rollback migration transaction", ex);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException e) {
                    log.warn("Failed to restore autoCommit state", e);
                }
            }
        }

        return new MigrationResult(appliedMigrations.size(), skippedCount, failedMigrations);
    }

    private MigrationStatus processMigration(Connection connection, String file) throws SQLException {
        Optional<MigrationRecord> existing = getMigrationRecord(connection, file);

        if (existing.isPresent()) {
            if (checksumVerification) {
                String currentChecksum = calculateChecksum(loadMigrationContent(file));
                if (!currentChecksum.equals(existing.get().checksum())) {
                    log.error("Migration checksum mismatch: {}. Expected: {}, Actual: {}",
                            file, existing.get().checksum(), currentChecksum);
                    return MigrationStatus.FAILED;
                }
            }
            log.debug("Migration already applied: {}", file);
            return MigrationStatus.SKIPPED;
        }

        log.info("Applying migration: {}", file);
        String sql = loadMigrationContent(file);

        if (sql.isEmpty()) {
            log.warn("Empty migration file: {}", file);
            return MigrationStatus.SKIPPED;
        }

        int attempt = 0;
        while (attempt < maxRetries) {
            attempt++;
            try {
                executeMigration(connection, sql);
                recordMigration(connection, file, calculateChecksum(sql));
                log.info("Migration applied successfully: {} (attempt {})", file, attempt);
                return MigrationStatus.APPLIED;
            } catch (SQLException e) {
                log.error("Migration attempt {} failed for {}: {}", e, attempt, file, e.getMessage());
                if (attempt >= maxRetries) {
                    log.error("Migration failed after {} attempts: {}", e, maxRetries, file);
                    return MigrationStatus.FAILED;
                }
                try {
                    Thread.sleep(retryDelayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new MigrationException("Migration interrupted", ie);
                }
            }
        }

        return MigrationStatus.FAILED;
    }

    private MigrationLock acquireLock(Connection connection) throws SQLException {
        try {
            String lockId = "swiftmapper_migration_lock";
            DatabaseType dbType = detectDatabaseType(connection);

            if (dbType == DatabaseType.POSTGRESQL) {
                return acquirePostgresLock(connection, lockId);
            } else if (dbType == DatabaseType.MYSQL) {
                return acquireMySqlLock(connection, lockId);
            } else {
                return acquireGenericLock(connection, lockId);
            }
        } catch (SQLException e) {
            log.error("Failed to acquire migration lock", e);
            return null;
        }
    }

    private DatabaseType detectDatabaseType(Connection connection) throws SQLException {
        String dbName = connection.getMetaData().getDatabaseProductName().toLowerCase();
        if (dbName.contains("postgresql")) return DatabaseType.POSTGRESQL;
        if (dbName.contains("mysql")) return DatabaseType.MYSQL;
        if (dbName.contains("h2")) return DatabaseType.H2;
        return DatabaseType.OTHER;
    }

    private MigrationLock acquirePostgresLock(Connection connection, String lockId) throws SQLException {
        String sql = """
            INSERT INTO swiftmapper_migration_locks (lock_id, acquired_at, process_info)
            VALUES (?, ?, ?)
            ON CONFLICT (lock_id) DO NOTHING
            RETURNING acquired_at
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, lockId);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setString(3, getProcessInfo());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MigrationLock(lockId, rs.getTimestamp(1).toInstant());
                }
            }
        }

        return checkAndBreakStaleLock(connection, lockId);
    }

    private MigrationLock acquireMySqlLock(Connection connection, String lockId) throws SQLException {
        String sql = """
            INSERT IGNORE INTO swiftmapper_migration_locks (lock_id, acquired_at, process_info)
            VALUES (?, ?, ?)
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, lockId);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setString(3, getProcessInfo());

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                return new MigrationLock(lockId, Instant.now());
            }
        }

        return checkAndBreakStaleLock(connection, lockId);
    }

    private MigrationLock acquireGenericLock(Connection connection, String lockId) throws SQLException {
        try {
            String insertSql = """
                INSERT INTO swiftmapper_migration_locks (lock_id, acquired_at, process_info)
                VALUES (?, ?, ?)
                """;

            try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
                stmt.setString(1, lockId);
                stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                stmt.setString(3, getProcessInfo());
                stmt.executeUpdate();
                return new MigrationLock(lockId, Instant.now());
            }
        } catch (SQLException e) {
            return checkAndBreakStaleLock(connection, lockId);
        }
    }

    private MigrationLock checkAndBreakStaleLock(Connection connection, String lockId) throws SQLException {
        String checkSql = "SELECT acquired_at, process_info FROM swiftmapper_migration_locks WHERE lock_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, lockId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp acquiredAt = rs.getTimestamp(1);
                    String processInfo = rs.getString(2);
                    log.warn("Migration lock held by {} since {}", processInfo, acquiredAt);

                    if (acquiredAt.toInstant().plusSeconds(300).isBefore(Instant.now())) {
                        log.warn("Lock appears stale, attempting to break");
                        String breakSql = "DELETE FROM swiftmapper_migration_locks WHERE lock_id = ?";
                        try (PreparedStatement breakStmt = connection.prepareStatement(breakSql)) {
                            breakStmt.setString(1, lockId);
                            if (breakStmt.executeUpdate() > 0) {
                                return acquireLock(connection);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void releaseLock(Connection connection, MigrationLock lock) {
        if (lock == null) return;

        try {
            String sql = "DELETE FROM swiftmapper_migration_locks WHERE lock_id = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, lock.lockId());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to release migration lock", e);
        }
    }

    private String getProcessInfo() {
        return String.format("%s@%s (pid:%d)",
                System.getProperty("user.name"),
                java.net.InetAddress.getLoopbackAddress().getHostName(),
                ProcessHandle.current().pid());
    }

    private void createMigrationTable(Connection connection) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS swiftmapper_migrations (
                filename VARCHAR(255) PRIMARY KEY,
                checksum VARCHAR(64),
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                execution_time_ms INTEGER
            )
            """;

        String lockTableSql = """
            CREATE TABLE IF NOT EXISTS swiftmapper_migration_locks (
                lock_id VARCHAR(255) PRIMARY KEY,
                acquired_at TIMESTAMP NOT NULL,
                process_info VARCHAR(500)
            )
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            stmt.execute(lockTableSql);
        }
    }

    private Optional<MigrationRecord> getMigrationRecord(Connection connection, String filename) throws SQLException {
        String sql = "SELECT filename, checksum, applied_at FROM swiftmapper_migrations WHERE filename = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, filename);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new MigrationRecord(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getTimestamp(3).toInstant()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private void recordMigration(Connection connection, String filename, String checksum) throws SQLException {
        String sql = "INSERT INTO swiftmapper_migrations (filename, checksum) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, filename);
            stmt.setString(2, checksum);
            stmt.executeUpdate();
        }
    }

    private List<String> loadMigrationFiles() {
        List<String> files = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        try {
            URL url = classLoader.getResource(migrationLocation);
            if (url == null) {
                log.warn("Migration location not found: {}", migrationLocation);
                return files;
            }

            if (url.getProtocol().equals("file")) {
                java.io.File dir = new java.io.File(url.toURI());
                if (dir.exists() && dir.isDirectory()) {
                    java.io.File[] sqlFiles = dir.listFiles((d, name) ->
                            name.endsWith(".sql") && !name.startsWith("."));
                    if (sqlFiles != null) {
                        for (java.io.File file : sqlFiles) {
                            files.add(file.getName());
                        }
                    }
                }
            } else if (url.getProtocol().equals("jar")) {
                JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
                try (java.util.jar.JarFile jar = jarConnection.getJarFile()) {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(migrationLocation + "/") && name.endsWith(".sql")) {
                            String fileName = name.substring(migrationLocation.length() + 1);
                            if (!fileName.contains("/") && !fileName.startsWith(".")) {
                                files.add(fileName);
                            }
                        }
                    }
                }
            }
            Collections.sort(files);
            log.debug("Found {} migration files", files.size());
        } catch (Exception e) {
            log.error("Could not load migration files: {}", e, e.getMessage());
        }
        return files;
    }

    private String loadMigrationContent(String filename) {
        String path = migrationLocation + "/" + filename;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(path)) {
            if (is != null) {
                String content = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));

                return content.replaceAll("--.*\\R", "")
                        .replaceAll("/\\*[\\s\\S]*?\\*/", "");
            }
        } catch (Exception e) {
            log.error("Failed to load migration: {}", e, filename);
        }
        return "";
    }

    private void executeMigration(Connection connection, String sql) throws SQLException {
        String[] statements = sql.split("(?i);\\s*(?=CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|GRANT|REVOKE)");

        try (Statement stmt = connection.createStatement()) {
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
        }
    }

    private String calculateChecksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return String.valueOf(content.hashCode());
        }
    }

    private enum MigrationStatus {
        APPLIED, SKIPPED, FAILED
    }

    private enum DatabaseType {
        POSTGRESQL, MYSQL, H2, OTHER
    }

    private record MigrationRecord(String filename, String checksum, Instant appliedAt) {}
    private record MigrationLock(String lockId, Instant acquiredAt) {}

    public record MigrationResult(int applied, int skipped, List<String> failed) {
        public boolean isSuccess() {
            return failed.isEmpty();
        }
    }

    public static class MigrationException extends RuntimeException {
        public MigrationException(String message) {
            super(message);
        }

        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}