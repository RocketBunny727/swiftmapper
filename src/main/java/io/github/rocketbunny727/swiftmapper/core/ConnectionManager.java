package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.*;
import io.github.rocketbunny727.swiftmapper.annotations.validation.Check;
import io.github.rocketbunny727.swiftmapper.cache.StatementCache;
import io.github.rocketbunny727.swiftmapper.config.DatasourceConfig;
import io.github.rocketbunny727.swiftmapper.config.ConfigReader;
import io.github.rocketbunny727.swiftmapper.config.ConnectionPool;
import io.github.rocketbunny727.swiftmapper.exception.ConnectionException;
import io.github.rocketbunny727.swiftmapper.migration.MigrationRunner;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import io.github.rocketbunny727.swiftmapper.repository.SwiftRepository;
import lombok.Getter;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConnectionManager {
    private final DatasourceConfig dsConfig;
    private final ConnectionPool connectionPool;
    private final String url;
    private final String username;
    private final String password;
    @Getter
    private final StatementCache statementCache;
    private static final SwiftLogger log = SwiftLogger.getLogger(ConnectionManager.class);

    public static ConnectionManager fromConfig() {
        ConfigReader configReader = new ConfigReader();

        ConfigReader.LoggingConfig loggingConfig = configReader.getLoggingConfig();
        SwiftLogger.setLevel(loggingConfig.level());
        SwiftLogger.setSqlLogging(loggingConfig.logSql());

        DatasourceConfig ds = configReader.getDatasourceConfig();
        log.info("Loaded datasource config: {}@{}", ds.username(), ds.url());

        ConfigReader.PoolConfig poolConfig = configReader.getPoolConfig();
        ConnectionManager manager = new ConnectionManager(ds, poolConfig);

        if (ds.migrationsLocation() != null && !ds.migrationsLocation().isBlank()) {
            MigrationRunner runner =
                    new MigrationRunner(manager.connectionPool.getDataSource(), ds.migrationsLocation());
            try {
                runner.runMigrations();
            } catch (SQLException e) {
                throw new ConnectionException("Failed to run migrations from " + ds.migrationsLocation(), e);
            }
        }

        return manager;
    }

    private ConnectionManager(DatasourceConfig dsConfig, ConfigReader.PoolConfig poolConfig) {
        this.connectionPool = new ConnectionPool(dsConfig, poolConfig);
        this.dsConfig = dsConfig;
        this.url = dsConfig.url();
        this.username = dsConfig.username();
        this.password = dsConfig.password();
        this.statementCache = new StatementCache(100);
    }

    public Connection getConnection() throws SQLException {
        return connectionPool.getConnection();
    }

    public ConnectionManager initSchema(Class<?>... entityClasses) throws SQLException {
        String ddlAuto = dsConfig.ddlAuto();
        if ("none".equalsIgnoreCase(ddlAuto)) {
            log.info("ddl-auto=none, skipping schema initialization");
            return this;
        }

        Connection connection = null;
        try {
            connection = getConnection();
            connection.setAutoCommit(true);

            if ("create".equalsIgnoreCase(ddlAuto) || "create-drop".equalsIgnoreCase(ddlAuto)) {
                log.info("ddl-auto={}, dropping existing tables...", ddlAuto);
                dropTables(connection, entityClasses);
            }

            if (!"validate".equalsIgnoreCase(ddlAuto)) {
                for (Class<?> entityClass : entityClasses) createTable(connection, entityClass);
                for (Class<?> entityClass : entityClasses) createJoinTables(connection, entityClass);
            } else {
                validateTables(connection, entityClasses);
            }

        } catch (SQLException e) {
            throw new ConnectionException("Failed to initialize schema", e);
        } finally {
            if (connection != null) {
                try { connection.setAutoCommit(false); } catch (SQLException ignored) {}
                try { connection.close(); } catch (SQLException e) { log.error("Failed to close connection", e); }
            }
        }
        return this;
    }

    private void dropTables(Connection connection, Class<?>... entityClasses) throws SQLException {
        for (int i = entityClasses.length - 1; i >= 0; i--) {
            String tableName = NamingStrategy.getTableName(entityClasses[i]);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + qi(tableName) + " CASCADE");
                log.info("Dropped table: {}", tableName);
            }
        }
    }

    private void validateTables(Connection connection, Class<?>... entityClasses) throws SQLException {
        for (Class<?> entityClass : entityClasses) {
            String tableName = NamingStrategy.getTableName(entityClass);
            String sql = "SELECT 1 FROM information_schema.tables WHERE table_name = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "ddl-auto=validate: table '" + tableName + "' not found in database");
                    }
                }
            }
            log.info("ddl-auto=validate: table '{}' OK", tableName);
        }
    }

    private static String qi(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("DDL identifier cannot be null or empty");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void validateDdlLiteral(String value, String context) {
        if (value == null) return;
        if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
            throw new SecurityException(
                    "Potentially dangerous SQL in " + context + ": " + value);
        }
    }

    private boolean constraintExists(Connection connection, String constraintName) {
        String sql = "SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, constraintName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private void createTable(Connection connection, Class<?> entityClass) throws SQLException {
        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw new IllegalArgumentException(entityClass + " must be @Entity");
        }

        Check classCheck = entityClass.getAnnotation(Check.class);
        if (classCheck != null) {
            log.info("Table {} has class-level check constraint: {}", entityClass.getSimpleName(), classCheck.value());
        }

        String tableName = NamingStrategy.getTableName(entityClass);

        List<String> columns = new ArrayList<>();
        List<String> tableConstraints = new ArrayList<>();
        Map<String, String> foreignKeys = new LinkedHashMap<>();
        List<String> indexes = new ArrayList<>();
        GeneratedValue idGen = null;
        Field idField = null;
        boolean needSequence = false;
        long sequenceStartValue = 1;

        for (Field field : entityClass.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Id.class)) {
                idField = field;
                idGen = field.getAnnotation(GeneratedValue.class);
                break;
            }
        }

        for (Field field : entityClass.getDeclaredFields()) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(Transient.class)) continue;

            if (field.isAnnotationPresent(Index.class)) {
                Index idx = field.getAnnotation(Index.class);
                String colName = NamingStrategy.getColumnName(field);
                String idxName = idx.name().isEmpty() ?
                        "idx_" + tableName + "_" + colName : idx.name();
                indexes.add(String.format("CREATE %sINDEX %s ON %s(%s)",
                        idx.unique() ? "UNIQUE " : "", qi(idxName), qi(tableName), qi(colName)));
            }

            if (field.isAnnotationPresent(Id.class)) {
                String colDef = createIdColumnDefinition(NamingStrategy.getIdColumnName(idField), idField, idGen);
                columns.add(colDef);

                if (idGen != null && (idGen.strategy() == Strategy.SEQUENCE ||
                        idGen.strategy() == Strategy.PATTERN)) {
                    needSequence = true;
                    sequenceStartValue = ValueSpec.parse(idGen.value(), idGen.strategy()).sequenceStart();
                }
            } else if (field.isAnnotationPresent(ManyToOne.class)) {
                JoinColumn jc = field.getAnnotation(JoinColumn.class);
                String fkColumn = NamingStrategy.getForeignKeyColumn(field);

                Class<?> targetClass = field.getType();
                String targetIdType = getTargetIdSqlType(targetClass);

                columns.add(qi(fkColumn) + " " + targetIdType);

                String targetTable = NamingStrategy.getTableName(targetClass);
                String referencedCol = jc != null && !jc.referencedColumnName().isEmpty() ?
                        jc.referencedColumnName() : NamingStrategy.getIdColumnName(getIdField(targetClass));

                String fkName = NamingStrategy.getFkConstraintName(tableName, fkColumn);
                foreignKeys.put(fkName, String.format(
                        "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s)",
                        qi(tableName), qi(fkName), qi(fkColumn), qi(targetTable), qi(referencedCol)
                ));
            } else if (field.isAnnotationPresent(OneToOne.class)) {
                OneToOne oo = field.getAnnotation(OneToOne.class);
                if (oo.mappedBy().isEmpty()) {
                    JoinColumn jc = field.getAnnotation(JoinColumn.class);
                    String fkColumn = NamingStrategy.getOneToOneFkColumn(field);

                    Class<?> targetClass = field.getType();
                    String targetIdType = getTargetIdSqlType(targetClass);

                    columns.add(qi(fkColumn) + " " + targetIdType);

                    String targetTable = NamingStrategy.getTableName(targetClass);
                    String referencedCol = jc != null && !jc.referencedColumnName().isEmpty() ?
                            jc.referencedColumnName() : NamingStrategy.getIdColumnName(getIdField(targetClass));

                    String fkName = NamingStrategy.getFkConstraintName(tableName, fkColumn);
                    foreignKeys.put(fkName, String.format(
                            "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s)",
                            qi(tableName), qi(fkName), qi(fkColumn), qi(targetTable), qi(referencedCol)
                    ));
                }
            } else if (!isRelationshipField(field)) {
                String colName = NamingStrategy.getColumnName(field);
                String sqlType = getSqlType(field, false, null);

                boolean isNullable = true;
                boolean isUnique = false;
                String defaultValue = null;
                String checkConstraint = null;

                if (field.isAnnotationPresent(Column.class)) {
                    Column col = field.getAnnotation(Column.class);
                    isNullable = col.nullable();
                    isUnique = col.unique();
                }

                if (field.isAnnotationPresent(DefaultValue.class)) {
                    DefaultValue dv = field.getAnnotation(DefaultValue.class);
                    defaultValue = dv.value();
                    validateDdlLiteral(defaultValue, "@DefaultValue on " + field.getName());
                }

                if (field.isAnnotationPresent(ColumnDefinition.class)) {
                    ColumnDefinition cd = field.getAnnotation(ColumnDefinition.class);
                    sqlType = cd.value();
                }

                if (field.isAnnotationPresent(Lob.class)) {
                    Lob lob = field.getAnnotation(Lob.class);
                    sqlType = lob.type() == Lob.LobType.BLOB ? "BLOB" : "CLOB";
                }

                if (field.isAnnotationPresent(Temporal.class)) {
                    Temporal temporal = field.getAnnotation(Temporal.class);
                    sqlType = switch (temporal.value()) {
                        case DATE -> "DATE";
                        case TIME -> "TIME";
                        case TIMESTAMP -> "TIMESTAMP";
                    };
                }

                String notNullClause = isNullable ? "" : " NOT NULL";
                String uniqueClause = isUnique ? " UNIQUE" : "";
                String defaultClause = defaultValue != null ? " DEFAULT " + defaultValue : "";

                if (field.isAnnotationPresent(Check.class)) {
                    Check check = field.getAnnotation(Check.class);
                    validateDdlLiteral(check.value(), "@Check on " + field.getName());
                    checkConstraint = " CHECK (" + check.value() + ")";
                }

                columns.add(qi(colName) + " " + sqlType + notNullClause + uniqueClause + defaultClause +
                        (checkConstraint != null ? checkConstraint : ""));
            }
        }

        if (classCheck != null) {
            validateDdlLiteral(classCheck.value(), "@Check on " + entityClass.getSimpleName());
            tableConstraints.add("CONSTRAINT " + qi("chk_" + tableName) + " CHECK (" + classCheck.value() + ")");
        }

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(qi(tableName)).append("(").append(String.join(", ", columns));

        if (!tableConstraints.isEmpty()) {
            sql.append(", ").append(String.join(", ", tableConstraints));
        }

        sql.append(")");

        log.showSQL(sql.toString());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql.toString());
            log.info("Created table: {}", tableName);
        }

        if (needSequence) {
            createSequence(connection, tableName, NamingStrategy.getIdColumnName(idField), sequenceStartValue);
        }

        for (Map.Entry<String, String> entry : foreignKeys.entrySet()) {
            if (!constraintExists(connection, entry.getKey())) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(entry.getValue());
                    log.showSQL(entry.getValue());
                } catch (SQLException e) {
                    log.warn("Error creating FK: {}", e.getMessage());
                }
            } else {
                log.debug("FK {} already exists", entry.getKey());
            }
        }

        for (String indexSql : indexes) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(indexSql);
                log.showSQL(indexSql);
            } catch (SQLException e) {
                log.warn("Failed to create index: {}", e.getMessage());
            }
        }
    }

    private void createJoinTables(Connection connection, Class<?> entityClass) {
        String ownerTableName = NamingStrategy.getTableName(entityClass);

        for (Field field : entityClass.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(ManyToMany.class)) {
                ManyToMany m2m = field.getAnnotation(ManyToMany.class);

                if (m2m.mappedBy().isEmpty()) {
                    try {
                        createJoinTableDefinition(connection, ownerTableName, entityClass, field);
                    } catch (Exception e) {
                        log.warn("Failed to create join table for field {}: {}", field.getName(), e.getMessage());
                    }
                }
            }
        }
    }

    private void createJoinTableDefinition(Connection connection, String ownerTableName, Class<?> ownerClass, Field field) throws SQLException {
        JoinTable jt = field.getAnnotation(JoinTable.class);
        Class<?> targetClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        String targetTableName = NamingStrategy.getTableName(targetClass);

        String joinTableName = NamingStrategy.getJoinTableName(ownerClass, targetClass, jt);
        String joinCol = NamingStrategy.getJoinColumnName(jt, ownerClass, true);
        String inverseCol = NamingStrategy.getJoinColumnName(jt, targetClass, false);

        String ownerIdType = getTargetIdSqlType(ownerClass);
        String targetIdType = getTargetIdSqlType(targetClass);

        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (%s %s NOT NULL, %s %s NOT NULL, PRIMARY KEY (%s, %s))",
                qi(joinTableName),
                qi(joinCol), ownerIdType,
                qi(inverseCol), targetIdType,
                qi(joinCol), qi(inverseCol));

        log.showSQL(sql);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }

        String fk1Name = NamingStrategy.getFkConstraintName(joinTableName, joinCol);
        String fk2Name = NamingStrategy.getFkConstraintName(joinTableName, inverseCol);

        String ownerIdCol = NamingStrategy.getIdColumnName(getIdField(ownerClass));
        String targetIdCol = NamingStrategy.getIdColumnName(getIdField(targetClass));

        addForeignKey(connection, joinTableName, fk1Name, joinCol, ownerTableName, ownerIdCol);
        addForeignKey(connection, joinTableName, fk2Name, inverseCol, targetTableName, targetIdCol);
    }

    private void addForeignKey(Connection connection, String table, String fkName, String col, String refTable, String refCol) {
        if (!constraintExists(connection, fkName)) {
            String sql = String.format(
                    "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s) ON DELETE CASCADE",
                    qi(table), qi(fkName), qi(col), qi(refTable), qi(refCol));
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
                log.showSQL(sql);
            } catch (SQLException e) {
                log.warn("Error creating FK {}: {}", fkName, e.getMessage());
            }
        }
    }

    private Field getIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
        }
        throw new IllegalStateException("No @Id field in " + clazz.getName());
    }

    private String getTargetIdSqlType(Class<?> targetClass) {
        Field idField = getIdField(targetClass);
        GeneratedValue gen = idField.getAnnotation(GeneratedValue.class);

        if (gen != null && gen.strategy() == Strategy.PATTERN) {
            return "VARCHAR(100)";
        }

        Class<?> type = idField.getType();
        if (type == String.class) {
            return "VARCHAR(255)";
        } else if (type == Long.class || type == long.class) {
            return "BIGINT";
        } else if (type == Integer.class || type == int.class) {
            return "INTEGER";
        }

        return "BIGINT";
    }

    private boolean isRelationshipField(Field field) {
        return field.isAnnotationPresent(OneToOne.class) ||
                field.isAnnotationPresent(OneToMany.class) ||
                field.isAnnotationPresent(ManyToOne.class) ||
                field.isAnnotationPresent(ManyToMany.class);
    }

    private String createIdColumnDefinition(String idColumn, Field idField, GeneratedValue gen) {
        String sqlType = getSqlType(idField, true, gen);

        if (gen != null && gen.strategy() == Strategy.PATTERN) {
            return qi(idColumn) + " " + sqlType + " PRIMARY KEY";
        } else if (gen != null && gen.strategy() == Strategy.IDENTITY) {
            return qi(idColumn) + " " + sqlType + " PRIMARY KEY";
        } else if (gen != null) {
            return qi(idColumn) + " BIGINT PRIMARY KEY";
        }
        return qi(idColumn) + " " + sqlType + " PRIMARY KEY";
    }

    private void createSequence(Connection connection, String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = NamingStrategy.getSequenceName(tableName, idColumn);

        String checkSql = "SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequencename = ?";
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, seqName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    log.info("Sequence {} already exists, skipping creation", seqName);
                    return;
                }
            }
        }

        String createSql = String.format(
                "CREATE SEQUENCE %s START WITH %d INCREMENT BY 1 NO MINVALUE NO MAXVALUE OWNED BY %s.%s",
                qi(seqName), startValue, qi(tableName), qi(idColumn)
        );

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createSql);
            log.info("Created sequence {} starting from {} (owned by {}.{})",
                    seqName, startValue, tableName, idColumn);
        }
    }

    private String getSqlType(Field field, boolean isId, GeneratedValue gen) {
        if (field.isAnnotationPresent(ColumnDefinition.class)) {
            ColumnDefinition cd = field.getAnnotation(ColumnDefinition.class);
            if (!cd.value().isEmpty()) {
                return cd.value();
            }
        }

        if (field.isAnnotationPresent(Column.class)) {
            String customType = field.getAnnotation(Column.class).sqlType();
            if (!customType.isEmpty()) return customType;
        }

        Class<?> type = field.getType();

        if (isId && gen != null && gen.strategy() == Strategy.PATTERN) {
            return "VARCHAR(100)";
        }

        if (isId && gen != null && gen.strategy() == Strategy.IDENTITY) {
            return type == Long.class || type == long.class ?
                    "BIGINT GENERATED BY DEFAULT AS IDENTITY" :
                    "INTEGER GENERATED BY DEFAULT AS IDENTITY";
        }

        if (field.isAnnotationPresent(Lob.class)) {
            Lob lob = field.getAnnotation(Lob.class);
            return lob.type() == Lob.LobType.BLOB ? "BLOB" : "CLOB";
        }

        if (field.isAnnotationPresent(Temporal.class)) {
            Temporal temporal = field.getAnnotation(Temporal.class);
            return switch (temporal.value()) {
                case DATE -> "DATE";
                case TIME -> "TIME";
                case TIMESTAMP -> "TIMESTAMP";
            };
        }

        return switch (type.getSimpleName()) {
            case "Long", "long" -> "BIGINT";
            case "Integer", "int" -> "INTEGER";
            case "Double", "double" -> "DOUBLE PRECISION";
            case "Float", "float" -> "REAL";
            case "Boolean", "boolean" -> "BOOLEAN";
            case "LocalDateTime" -> "TIMESTAMP";
            case "LocalDate" -> "DATE";
            case "LocalTime" -> "TIME";
            case "byte[]", "Byte[]" -> "BYTEA";
            default -> "VARCHAR(255)";
        };
    }

    public void close() {
        if ("create-drop".equalsIgnoreCase(dsConfig.ddlAuto())) {
            log.info("ddl-auto=create-drop, dropping tables on shutdown...");
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                log.warn("Failed to drop tables on shutdown: {}", e.getMessage());
            }
        }
        statementCache.clear();
        connectionPool.close();
        log.info("Connection pool closed");
    }

    public <T, ID> SwiftRepository<T, ID> repository(Class<T> entityClass, Class<ID> idClass) {
        return new SwiftRepository<>(this, entityClass, idClass);
    }
}