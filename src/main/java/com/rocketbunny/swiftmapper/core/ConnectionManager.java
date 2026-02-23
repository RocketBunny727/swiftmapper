package com.rocketbunny.swiftmapper.core;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.*;
import com.rocketbunny.swiftmapper.cache.StatementCache;
import com.rocketbunny.swiftmapper.config.DatasourceConfig;
import com.rocketbunny.swiftmapper.config.ConfigReader;
import com.rocketbunny.swiftmapper.config.ConnectionPool;
import com.rocketbunny.swiftmapper.exception.ConnectionException;
import com.rocketbunny.swiftmapper.utils.naming.NamingStrategy;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;
import com.rocketbunny.swiftmapper.repository.SwiftRepository;

import java.lang.reflect.Field;
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
    private final ConnectionPool connectionPool;
    private final String url;
    private final String username;
    private final String password;
    private final StatementCache statementCache;
    private static final SwiftLogger log = SwiftLogger.getLogger(ConnectionManager.class);

    public static ConnectionManager fromConfig() {
        ConfigReader configReader = new ConfigReader();
        DatasourceConfig ds = configReader.getDatasourceConfig();
        log.info("Loaded datasource config: {}@{}", ds.getUsername(), ds.getUrl());
        return new ConnectionManager(ds);
    }

    private ConnectionManager(DatasourceConfig dsConfig) {
        this.connectionPool = new ConnectionPool(dsConfig);
        this.url = dsConfig.getUrl();
        this.username = dsConfig.getUsername();
        this.password = dsConfig.getPassword();
        this.statementCache = new StatementCache(100);
    }

    public Connection getConnection() throws SQLException {
        return connectionPool.getConnection();
    }

    public StatementCache getStatementCache() {
        return statementCache;
    }

    public ConnectionManager initSchema(Class<?>... entityClasses) throws SQLException {
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(true);

            for (Class<?> entityClass : entityClasses) {
                createTable(connection, entityClass);
            }

            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new ConnectionException("Failed to initialize schema", e);
        }
        return this;
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

        String tableName = NamingStrategy.getTableName(entityClass);

        List<String> columns = new ArrayList<>();
        Map<String, String> foreignKeys = new LinkedHashMap<>();
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

            if (field.isAnnotationPresent(Id.class)) {
                String colDef = createIdColumnDefinition(NamingStrategy.getIdColumnName(idField), idField, idGen);
                columns.add(colDef);

                if (idGen != null && (idGen.strategy() == Strategy.SEQUENCE ||
                        idGen.strategy() == Strategy.PATTERN ||
                        idGen.strategy() == Strategy.ALPHA)) {
                    needSequence = true;
                    sequenceStartValue = idGen.startValue();
                }
            } else if (field.isAnnotationPresent(ManyToOne.class)) {
                JoinColumn jc = field.getAnnotation(JoinColumn.class);
                String fkColumn = NamingStrategy.getForeignKeyColumn(field);

                Class<?> targetClass = field.getType();
                String targetIdType = getTargetIdSqlType(targetClass);

                columns.add(fkColumn + " " + targetIdType);

                String targetTable = NamingStrategy.getTableName(targetClass);
                String referencedCol = jc != null && !jc.referencedColumnName().isEmpty() ?
                        jc.referencedColumnName() : NamingStrategy.getIdColumnName(getIdField(targetClass));

                String fkName = NamingStrategy.getFkConstraintName(tableName, fkColumn);
                foreignKeys.put(fkName, String.format(
                        "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s)",
                        tableName, fkName, fkColumn, targetTable, referencedCol
                ));
            } else if (field.isAnnotationPresent(OneToOne.class)) {
                OneToOne oo = field.getAnnotation(OneToOne.class);
                if (oo.mappedBy().isEmpty()) {
                    JoinColumn jc = field.getAnnotation(JoinColumn.class);
                    String fkColumn = NamingStrategy.getOneToOneFkColumn(field);

                    Class<?> targetClass = field.getType();
                    String targetIdType = getTargetIdSqlType(targetClass);

                    columns.add(fkColumn + " " + targetIdType);

                    String targetTable = NamingStrategy.getTableName(targetClass);
                    String referencedCol = jc != null && !jc.referencedColumnName().isEmpty() ?
                            jc.referencedColumnName() : NamingStrategy.getIdColumnName(getIdField(targetClass));

                    String fkName = NamingStrategy.getFkConstraintName(tableName, fkColumn);
                    foreignKeys.put(fkName, String.format(
                            "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s)",
                            tableName, fkName, fkColumn, targetTable, referencedCol
                    ));
                }
            } else if (!isRelationshipField(field)) {
                String colName = NamingStrategy.getColumnName(field);
                String sqlType = getSqlType(field, false, null);
                columns.add(colName + " " + sqlType);
            }
        }

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(tableName).append("(").append(String.join(", ", columns)).append(")");

        log.info("Creating table: {}", sql);
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
                    log.info("Created FK: {}", entry.getValue());
                } catch (SQLException e) {
                    log.warn("Error creating FK: {}", e.getMessage());
                }
            } else {
                log.info("FK {} already exists", entry.getKey());
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
            return idColumn + " " + sqlType + " PRIMARY KEY";
        } else if (gen != null && gen.strategy() == Strategy.IDENTITY) {
            return idColumn + " " + sqlType + " PRIMARY KEY";
        } else if (gen != null && gen.strategy() == Strategy.ALPHA) {
            return idColumn + " BIGINT PRIMARY KEY";
        }
        return idColumn + " " + sqlType + " PRIMARY KEY";
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
                seqName, startValue, tableName, idColumn
        );

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createSql);
            log.info("Created sequence {} starting from {} (owned by {}.{})",
                    seqName, startValue, tableName, idColumn);
        }
    }

    private String getSqlType(Field field, boolean isId, GeneratedValue gen) {
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

        return switch (type.getSimpleName()) {
            case "Long", "long" -> "BIGINT";
            case "Integer", "int" -> "INTEGER";
            case "Double", "double" -> "DOUBLE PRECISION";
            case "Boolean", "boolean" -> "BOOLEAN";
            case "LocalDateTime" -> "TIMESTAMP";
            case "LocalDate" -> "DATE";
            default -> "VARCHAR(255)";
        };
    }

    public void close() {
        statementCache.clear();
        connectionPool.close();
        log.info("Connection pool closed");
    }

    public <T> SwiftRepository<T, Long> repository(Class<T> entityClass) {
        return new SwiftRepository<>(this, entityClass);
    }
}