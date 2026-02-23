package com.rocketbunny.swiftmapper.migration;

import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MigrationRunner {
    private final DataSource dataSource;
    private final SwiftLogger log = SwiftLogger.getLogger(MigrationRunner.class);
    private final String migrationLocation;

    public MigrationRunner(DataSource dataSource, String migrationLocation) {
        this.dataSource = dataSource;
        this.migrationLocation = migrationLocation;
    }

    public void runMigrations() throws SQLException {
        log.info("Running migrations from {}", migrationLocation);

        List<String> migrationFiles = loadMigrationFiles();

        try (Connection connection = dataSource.getConnection()) {
            createMigrationTable(connection);

            for (String file : migrationFiles) {
                if (!isMigrationApplied(connection, file)) {
                    log.info("Applying migration: {}", file);
                    String sql = loadMigrationContent(file);
                    executeMigration(connection, sql);
                    recordMigration(connection, file);
                    log.info("Migration applied: {}", file);
                } else {
                    log.debug("Migration already applied: {}", file);
                }
            }
        }
    }

    private List<String> loadMigrationFiles() {
        List<String> files = new ArrayList<>();
        try {
            java.net.URL url = getClass().getClassLoader().getResource(migrationLocation);
            if (url == null) {
                log.warn("Migration location not found: {}", migrationLocation);
                return files;
            }

            if (url.getProtocol().equals("file")) {
                java.io.File dir = new java.io.File(url.toURI());
                if (dir.exists() && dir.isDirectory()) {
                    java.io.File[] sqlFiles = dir.listFiles((d, name) -> name.endsWith(".sql"));
                    if (sqlFiles != null) {
                        for (java.io.File file : sqlFiles) {
                            files.add(file.getName());
                        }
                    }
                }
            } else if (url.getProtocol().equals("jar")) {
                String jarPath = url.getPath().substring(5, url.getPath().indexOf("!"));
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8))) {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        java.util.jar.JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(migrationLocation + "/") && name.endsWith(".sql") && !name.equals(migrationLocation + "/")) {
                            files.add(name.substring(migrationLocation.length() + 1));
                        }
                    }
                }
            }

            files.sort(String::compareTo);
        } catch (Exception e) {
            log.warn("Could not load migration files: {}", e.getMessage());
        }
        return files;
    }

    private String loadMigrationContent(String filename) {
        String path = migrationLocation + "/" + filename;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.error("Failed to load migration: {}", e, filename);
        }
        return "";
    }

    private void createMigrationTable(Connection connection) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS swiftmapper_migrations (
                id SERIAL PRIMARY KEY,
                filename VARCHAR(255) NOT NULL UNIQUE,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private boolean isMigrationApplied(Connection connection, String filename) throws SQLException {
        String sql = "SELECT 1 FROM swiftmapper_migrations WHERE filename = ?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, filename);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void executeMigration(Connection connection, String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void recordMigration(Connection connection, String filename) throws SQLException {
        String sql = "INSERT INTO swiftmapper_migrations (filename) VALUES (?)";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, filename);
            stmt.executeUpdate();
        }
    }
}