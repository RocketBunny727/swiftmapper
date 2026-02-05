package ru.nsu.swiftmapper.core;

import ru.nsu.swiftmapper.annotations.entity.*;
import ru.nsu.swiftmapper.annotations.relationship.*;
import ru.nsu.swiftmapper.config.ConfigReader;
import ru.nsu.swiftmapper.logger.SwiftLogger;
import ru.nsu.swiftmapper.repository.SwiftRepository;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ConnectionManager {
    private final ConfigReader.DatasourceConfig dsConfig;
    private final String url;
    private final String username;
    private final String password;
    private Connection connection;
    private static final SwiftLogger log = SwiftLogger.getLogger(ConnectionManager.class);

    public static ConnectionManager fromConfig() {
        ConfigReader configReader = new ConfigReader();
        ConfigReader.DatasourceConfig ds = configReader.getDatasourceConfig();
        log.info("Loaded datasource config: {}@{}",  ds.username, ds.url);
        return new ConnectionManager(ds);
    }

    private ConnectionManager(String url, String username, String password) {
        this.dsConfig = null;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    private ConnectionManager(ConfigReader.DatasourceConfig dsConfig) {
        this.dsConfig = dsConfig;
        this.url = dsConfig.url;
        this.username = dsConfig.username;
        this.password = dsConfig.password;
    }

    public ConnectionManager connect() throws SQLException {
        try {
            String driver = (dsConfig != null ? dsConfig.driverClassName : "org.h2.Driver");
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            log.warn("Driver not found: {}, using auto-detection", e.getMessage());
        }

        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url, username, password);
            log.info("Connected to: {}", url);
        }
        return this;
    }

    public Connection connection() {
        return connection;
    }

    public ConnectionManager initSchema(Class<?>... entityClasses) throws SQLException {
        connect();
        connection.setAutoCommit(true);

        for (Class<?> entityClass : entityClasses) {
            createTable(entityClass);
        }
        return this;
    }

    private void createTable(Class<?> entityClass) throws SQLException {
        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw new IllegalArgumentException(entityClass + " must be @Entity");
        }

        EntityMapper<?> mapper = new EntityMapper<>(entityClass, connection);
        String tableName = mapper.getTableName();

        List<String> columns = new ArrayList<>();
        List<String> foreignKeys = new ArrayList<>();
        GeneratedValue idGen = null;
        Field idField = null;

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
                String colDef = createIdColumnDefinition(mapper.getIdColumn(), idField, idGen);
                columns.add(colDef);

                if (idGen != null && (idGen.strategy() == Strategy.PATTERN ||
                        idGen.strategy() == Strategy.ALPHA)) {
                    createCustomSequence(tableName, mapper.getIdColumn(), idGen.startValue());
                }
            } else if (field.isAnnotationPresent(ManyToOne.class)) {
                JoinColumn jc = field.getAnnotation(JoinColumn.class);
                String fkColumn = jc != null && !jc.name().isEmpty() ?
                        jc.name() : field.getName().toLowerCase() + "_id";

                Class<?> targetClass = field.getType();
                String targetIdType = getTargetIdSqlType(targetClass);

                columns.add(fkColumn + " " + targetIdType);

                EntityMapper<?> targetMapper = new EntityMapper<>(targetClass, connection);
                String targetTable = targetMapper.getTableName();
                String referencedCol = jc != null && !jc.referencedColumnName().isEmpty() ?
                        jc.referencedColumnName() : targetMapper.getIdColumn();

                foreignKeys.add(String.format(
                        "ALTER TABLE %s ADD CONSTRAINT fk_%s_%s FOREIGN KEY (%s) REFERENCES %s(%s)",
                        tableName, tableName, fkColumn, fkColumn, targetTable, referencedCol
                ));
            } else if (field.isAnnotationPresent(OneToOne.class)) {
                OneToOne oo = field.getAnnotation(OneToOne.class);
                if (oo.mappedBy().isEmpty()) {
                    JoinColumn jc = field.getAnnotation(JoinColumn.class);
                    String fkColumn = jc != null && !jc.name().isEmpty() ?
                            jc.name() : field.getName().toLowerCase() + "_id";

                    Class<?> targetClass = field.getType();
                    String targetIdType = getTargetIdSqlType(targetClass);

                    columns.add(fkColumn + " " + targetIdType);

                    EntityMapper<?> targetMapper = new EntityMapper<>(targetClass, connection);
                    String targetTable = targetMapper.getTableName();
                    String referencedCol = jc != null && !jc.referencedColumnName().isEmpty() ?
                            jc.referencedColumnName() : targetMapper.getIdColumn();

                    foreignKeys.add(String.format(
                            "ALTER TABLE %s ADD CONSTRAINT fk_%s_%s FOREIGN KEY (%s) REFERENCES %s(%s)",
                            tableName, tableName, fkColumn, fkColumn, targetTable, referencedCol
                    ));
                }
            } else if (!isRelationshipField(field)) {
                String colName = getColumnName(field);
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

        for (String fkSql : foreignKeys) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(fkSql);
                log.info("Created FK: {}", fkSql);
            } catch (SQLException e) {
                log.warn("FK may already exist or error: {}", e.getMessage());
            }
        }
    }

    private String getTargetIdSqlType(Class<?> targetClass) {
        Field idField = null;
        GeneratedValue gen = null;

        for (Field field : targetClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                idField = field;
                gen = field.getAnnotation(GeneratedValue.class);
                break;
            }
        }

        if (idField == null) {
            return "BIGINT";
        }

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

    private String getColumnName(Field field) {
        if (field.isAnnotationPresent(Column.class)) {
            String name = field.getAnnotation(Column.class).name();
            if (!name.isEmpty()) return name;
        }
        return field.getName().toLowerCase();
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

    private void createCustomSequence(String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = tableName + "_" + idColumn + "_custom_seq";

        String dropSql = String.format("DROP SEQUENCE IF EXISTS %s CASCADE", seqName);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(dropSql);
        }

        String createSql = String.format(
                "CREATE SEQUENCE %s START WITH %d INCREMENT BY 1 NO MINVALUE NO MAXVALUE",
                seqName, startValue
        );

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createSql);
            log.info("Created custom sequence {} starting from {}", seqName, startValue);
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

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            log.info("Connection closed");
        }
    }

    public <T> SwiftRepository<T, Long> repository(Class<T> entityClass) {
        return new SwiftRepository<>(connection, entityClass);
    }

    private void createForeignKeys(Class<?> entityClass) throws SQLException {
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(ManyToOne.class) ||
                    (field.isAnnotationPresent(OneToOne.class) &&
                            field.getAnnotation(OneToOne.class).mappedBy().isEmpty())) {

                JoinColumn jc = field.getAnnotation(JoinColumn.class);
                if (jc == null) continue;

                String fkName = jc.name().isEmpty() ?
                        field.getName().toLowerCase() + "_id" : jc.name();
                String targetTable = field.getType().getAnnotation(Table.class).name();
                if (targetTable.isEmpty()) targetTable = field.getType().getSimpleName().toLowerCase();

                EntityMapper<?> targetMapper = new EntityMapper<>(field.getType(), connection);
                String referencedCol = jc.referencedColumnName();

                String sql = String.format(
                        "ALTER TABLE %s ADD CONSTRAINT fk_%s_%s FOREIGN KEY (%s) REFERENCES %s(%s)",
                        getTableName(entityClass), getTableName(entityClass), fkName,
                        fkName, targetTable, referencedCol
                );

                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(sql);
                    log.info("Created FK: {}", fkName);
                } catch (SQLException e) {
                    log.warn("FK may already exist: {}", e.getMessage());
                }
            }
        }
    }

    private String getTableName(Class<?> clazz) {
        Table table = clazz.getAnnotation(Table.class);
        return table != null && !table.name().isEmpty() ? table.name()
                : clazz.getSimpleName().toLowerCase();
    }
}