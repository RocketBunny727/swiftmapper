package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.*;
import io.github.rocketbunny727.swiftmapper.annotations.validation.Check;
import io.github.rocketbunny727.swiftmapper.cache.StatementCache;
import io.github.rocketbunny727.swiftmapper.config.DatasourceConfig;
import io.github.rocketbunny727.swiftmapper.config.ConfigReader;
import io.github.rocketbunny727.swiftmapper.config.ConnectionPool;
import io.github.rocketbunny727.swiftmapper.dialect.SqlDialect;
import io.github.rocketbunny727.swiftmapper.dialect.SqlRenderer;
import io.github.rocketbunny727.swiftmapper.exception.ConnectionException;
import io.github.rocketbunny727.swiftmapper.migration.MigrationRunner;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import io.github.rocketbunny727.swiftmapper.repository.SwiftRepository;
import lombok.Getter;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class ConnectionManager {
    private final DatasourceConfig dsConfig;
    private final ConnectionPool connectionPool;
    private final String url;
    private final String username;
    private final String password;
    @Getter
    private final StatementCache statementCache;
    @Getter
    private final SqlDialect dialect;
    @Getter
    private final SqlRenderer sqlRenderer;
    private static final SwiftLogger log = SwiftLogger.getLogger(ConnectionManager.class);

    private static volatile ConnectionManager instance;
    private static final Object lock = new Object();

    public static ConnectionManager fromConfig() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
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
                                new MigrationRunner(manager.connectionPool.getDataSource(), ds.migrationsLocation(),
                                        manager.getDialect());
                        try {
                            runner.runMigrations();
                        } catch (SQLException e) {
                            throw new ConnectionException("Failed to run migrations from " + ds.migrationsLocation(), e);
                        }
                    }

                    instance = manager;
                }
            }
        }
        return instance;
    }

    private ConnectionManager(DatasourceConfig dsConfig, ConfigReader.PoolConfig poolConfig) {
        this.connectionPool = new ConnectionPool(dsConfig, poolConfig);
        this.dsConfig = dsConfig;
        this.url = dsConfig.url();
        this.username = dsConfig.username();
        this.password = dsConfig.password();
        this.statementCache = new StatementCache(100);
        this.dialect = resolveDialect(dsConfig);
        this.sqlRenderer = new SqlRenderer(dialect);
        log.info("Using SQL dialect: {}", dialect.name());
    }

    private SqlDialect resolveDialect(DatasourceConfig config) {
        String productName = null;
        try (Connection connection = connectionPool.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            log.warn("Could not read database product name, resolving dialect from config/url: {}", e.getMessage());
        }
        return SqlDialect.resolve(config.dialect(), config.url(), config.driverClassName(), productName);
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
                entityClasses = sortByDependency(entityClasses);
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

    private Class<?>[] sortByDependency(Class<?>[] entityClasses) {
        Set<Class<?>> entitySet = new HashSet<>(Arrays.asList(entityClasses));

        Map<Class<?>, Set<Class<?>>> dependsOn = new LinkedHashMap<>();
        for (Class<?> cls : entityClasses) {
            Set<Class<?>> deps = new LinkedHashSet<>();
            for (Field field : cls.getDeclaredFields()) {
                if (field.isAnnotationPresent(ManyToOne.class)) {
                    Class<?> target = field.getType();
                    if (entitySet.contains(target) && target != cls) deps.add(target);
                } else if (field.isAnnotationPresent(OneToOne.class)) {
                    OneToOne oo = field.getAnnotation(OneToOne.class);
                    if (oo.mappedBy().isEmpty()) {
                        Class<?> target = field.getType();
                        if (entitySet.contains(target) && target != cls) deps.add(target);
                    }
                }
            }
            dependsOn.put(cls, deps);
        }

        Map<Class<?>, Integer> inDegree = new LinkedHashMap<>();
        Map<Class<?>, List<Class<?>>> dependents = new LinkedHashMap<>();

        for (Class<?> cls : entityClasses) {
            inDegree.put(cls, dependsOn.get(cls).size());
            dependents.put(cls, new ArrayList<>());
        }
        for (Class<?> cls : entityClasses) {
            for (Class<?> dep : dependsOn.get(cls)) {
                dependents.get(dep).add(cls);
            }
        }

        Queue<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> cls : entityClasses) {
            if (inDegree.get(cls) == 0) queue.add(cls);
        }

        List<Class<?>> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Class<?> cls = queue.poll();
            sorted.add(cls);
            for (Class<?> dependent : dependents.get(cls)) {
                int deg = inDegree.get(dependent) - 1;
                inDegree.put(dependent, deg);
                if (deg == 0) queue.add(dependent);
            }
        }

        if (sorted.size() != entityClasses.length) {
            log.warn("Circular dependency detected among @Entity classes; " +
                            "falling back to original order. Affected classes: {}",
                    Arrays.stream(entityClasses)
                            .filter(c -> !sorted.contains(c))
                            .map(Class::getSimpleName)
                            .toList());
            return entityClasses;
        }

        log.info("Entity creation order after dependency sort: {}",
                sorted.stream().map(Class::getSimpleName).toList());
        return sorted.toArray(new Class[0]);
    }

    private void dropTables(Connection connection, Class<?>... entityClasses) throws SQLException {
        for (int i = entityClasses.length - 1; i >= 0; i--) {
            String tableName = NamingStrategy.getTableName(entityClasses[i]);
            String sql = dialect.dropTableIfExists(tableName);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
                log.showSQL(sql);
                log.info("Dropped table: {}", tableName);
            }
        }
    }

    private void validateTables(Connection connection, Class<?>... entityClasses) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (Class<?> entityClass : entityClasses) {
            String tableName = NamingStrategy.getTableName(entityClass);
            boolean exists = false;
            try (ResultSet rs = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
                exists = rs.next();
            }
            if (!exists) {
                try (ResultSet rs = metaData.getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                throw new IllegalStateException(
                        "ddl-auto=validate: table '" + tableName + "' not found in database");
            }
            log.info("ddl-auto=validate: table '{}' OK", tableName);
        }
    }

    private String qi(String identifier) {
        return dialect.quoteIdentifier(identifier);
    }

    private static void validateDdlLiteral(String value, String context) {
        if (value == null) return;
        if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
            throw new SecurityException(
                    "Potentially dangerous SQL in " + context + ": " + value);
        }
    }

    private boolean constraintExists(Connection connection, String constraintName) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getImportedKeys(null, null, null)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    if (constraintName.equalsIgnoreCase(fkName)) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Could not inspect FK metadata for {}: {}", constraintName, e.getMessage());
        }
        return false;
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
                indexes.add(dialect.createIndexSql(idx.unique(), idxName, tableName, colName));
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
                foreignKeys.put(fkName, dialect.addForeignKeySql(
                        tableName, fkName, fkColumn, targetTable, referencedCol, false));
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
                    foreignKeys.put(fkName, dialect.addForeignKeySql(
                            tableName, fkName, fkColumn, targetTable, referencedCol, false));
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
                    sqlType = lob.type() == Lob.LobType.BLOB ? dialect.binaryType() : dialect.clobType();
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

        StringBuilder definition = new StringBuilder(String.join(", ", columns));

        if (!tableConstraints.isEmpty()) {
            definition.append(", ").append(String.join(", ", tableConstraints));
        }

        String sql = dialect.createTableIfNotExists(tableName, definition.toString());

        log.showSQL(sql);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            log.info("Created table: {}", tableName);
        }

        if (needSequence) {
            createSequence(connection, tableName, NamingStrategy.getIdColumnName(idField), sequenceStartValue);
        }

        for (Map.Entry<String, String> entry : foreignKeys.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                log.warn("Skipping FK {} for dialect {}", entry.getKey(), dialect.name());
                continue;
            }
            if (!constraintExists(connection, entry.getKey())) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(entry.getValue());
                    log.showSQL(entry.getValue());
                } catch (UnsupportedOperationException e) {
                    log.warn("Skipping FK {} for dialect {}: {}", entry.getKey(), dialect.name(), e.getMessage());
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

        String definition = String.format(
                "%s %s NOT NULL, %s %s NOT NULL, PRIMARY KEY (%s, %s)",
                qi(joinCol), ownerIdType,
                qi(inverseCol), targetIdType,
                qi(joinCol), qi(inverseCol));
        String sql = dialect.createTableIfNotExists(joinTableName, definition);

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
        String sql = dialect.addForeignKeySql(table, fkName, col, refTable, refCol, true);
        if (sql == null || sql.isBlank()) {
            log.warn("Skipping FK {} for dialect {}", fkName, dialect.name());
            return;
        }
        if (!constraintExists(connection, fkName)) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
                log.showSQL(sql);
            } catch (UnsupportedOperationException e) {
                log.warn("Skipping FK {} for dialect {}: {}", fkName, dialect.name(), e.getMessage());
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
            return dialect.stringType(100);
        }

        return dialect.sqlType(idField.getType());
    }

    private boolean isRelationshipField(Field field) {
        return field.isAnnotationPresent(OneToOne.class) ||
                field.isAnnotationPresent(OneToMany.class) ||
                field.isAnnotationPresent(ManyToOne.class) ||
                field.isAnnotationPresent(ManyToMany.class);
    }

    private String createIdColumnDefinition(String idColumn, Field idField, GeneratedValue gen) {
        return dialect.idColumnDefinition(idColumn, idField.getType(), gen);
    }

    private void createSequence(Connection connection, String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = NamingStrategy.getSequenceName(tableName, idColumn);

        String checkSql = dialect.sequenceExistsSql();
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, dialect.sequenceLookupValue(seqName));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    log.info("Sequence {} already exists, skipping creation", seqName);
                    return;
                }
            }
        }

        String createSql = dialect.createSequenceSql(seqName, tableName, idColumn, startValue);

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
            return dialect.stringType(100);
        }

        if (isId && gen != null && gen.strategy() == Strategy.IDENTITY) {
            return dialect.identityColumnType(type);
        }

        if (field.isAnnotationPresent(Lob.class)) {
            Lob lob = field.getAnnotation(Lob.class);
            return lob.type() == Lob.LobType.BLOB ? dialect.binaryType() : dialect.clobType();
        }

        if (field.isAnnotationPresent(Temporal.class)) {
            Temporal temporal = field.getAnnotation(Temporal.class);
            return switch (temporal.value()) {
                case DATE -> "DATE";
                case TIME -> "TIME";
                case TIMESTAMP -> "TIMESTAMP";
            };
        }

        return dialect.sqlType(type);
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

    public static void resetInstance() {
        synchronized (lock) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }
}