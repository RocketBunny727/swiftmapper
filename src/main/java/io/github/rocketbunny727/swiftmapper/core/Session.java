package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.*;
import io.github.rocketbunny727.swiftmapper.cache.QueryCache;
import io.github.rocketbunny727.swiftmapper.cache.StatementCache;
import io.github.rocketbunny727.swiftmapper.cascade.CascadeHandler;
import io.github.rocketbunny727.swiftmapper.config.ConfigReader;
import io.github.rocketbunny727.swiftmapper.exception.EntityNotFoundException;
import io.github.rocketbunny727.swiftmapper.exception.MappingException;
import io.github.rocketbunny727.swiftmapper.exception.QueryException;
import io.github.rocketbunny727.swiftmapper.exception.ValidationException;
import io.github.rocketbunny727.swiftmapper.monitoring.MetricsCollector;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import io.github.rocketbunny727.swiftmapper.utils.logger.QueryLogger;
import io.github.rocketbunny727.swiftmapper.utils.logger.QueryLogger.QueryLogEntry;
import io.github.rocketbunny727.swiftmapper.utils.logger.QueryLogger.TransactionLogEntry;
import io.github.rocketbunny727.swiftmapper.utils.validation.Validator;
import lombok.Getter;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

public class Session<T> {
    private static final Pattern ALLOWED_QUERY_PATTERN = Pattern.compile(
            "^\\s*SELECT\\s+[a-zA-Z0-9_*,\\s\\(\\)\\.\\[\\]\"]+\\s+FROM\\s+[a-zA-Z0-9_\"\\.]+.*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(;|--|/\\*|\\*/|'|\\\"|\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|UNION|INTO|LOAD_FILE|BULK|INSERT)\\b)",
            Pattern.CASE_INSENSITIVE
    );

    private final ConnectionManager connectionManager;
    private final Class<T> entityClass;
    private final SwiftLogger log = SwiftLogger.getLogger(Session.class);
    @Getter
    private final QueryLogger queryLogger;
    @Getter
    private final MetricsCollector metricsCollector;
    private static final QueryCache queryCache = new QueryCache();
    private static final java.util.concurrent.atomic.AtomicBoolean cacheConfigured =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private Connection dedicatedConnection;
    private boolean externalConnection = false;
    private boolean connectionOwner = false;
    private boolean externalTransaction = false;
    private StatementCache statementCache;
    private int queryTimeout = 30;
    private int batchSize = 100;
    private boolean cacheEnabled = true;

    public Session(ConnectionManager connectionManager, Class<T> entityClass) {
        this.connectionManager = connectionManager;
        this.entityClass = entityClass;
        this.statementCache = connectionManager.getStatementCache();
        this.queryLogger = new QueryLogger();
        this.metricsCollector = MetricsCollector.getInstance();

        ConfigReader configReader = new ConfigReader();
        ConfigReader.CacheConfig cacheConfig = configReader.getCacheConfig();
        this.cacheEnabled = cacheConfig.enabled();
        if (cacheConfigured.compareAndSet(false, true)) {
            queryCache.configure(cacheConfig.enabled(), cacheConfig.maxSize(),
                    cacheConfig.expireMinutes(), cacheConfig.providerClass());
        }

        log.info("Session created for {}", entityClass.getSimpleName());
    }

    public Session(Connection connection, Class<T> entityClass) {
        this.connectionManager = null;
        this.entityClass = entityClass;
        this.dedicatedConnection = connection;
        this.externalConnection = true;
        this.statementCache = null;
        this.queryLogger = new QueryLogger();
        this.metricsCollector = MetricsCollector.getInstance();
        log.info("Session created for {} with external connection", entityClass.getSimpleName());
    }

    public void setStatementCache(StatementCache cache) {
        this.statementCache = cache;
    }

    public void setExternalTransaction(boolean externalTransaction) {
        this.externalTransaction = externalTransaction;
    }

    public void setQueryTimeout(int seconds) {
        this.queryTimeout = seconds;
    }

    public void setBatchSize(int size) {
        this.batchSize = size;
    }

    public void setCacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
    }

    private Connection acquireConnection() throws SQLException {
        Instant start = Instant.now();
        Connection conn;

        if (dedicatedConnection != null) {
            conn = dedicatedConnection;
        } else if (connectionManager != null) {
            dedicatedConnection = connectionManager.getConnection();
            connectionOwner = true;
            conn = dedicatedConnection;
        } else {
            throw new SQLException("No connection source available");
        }

        Duration waitTime = Duration.between(start, Instant.now());
        metricsCollector.recordConnectionAcquired(waitTime);

        return conn;
    }

    private void releaseConnection() {
        if (connectionOwner && dedicatedConnection != null) {
            try {
                dedicatedConnection.close();
            } catch (SQLException e) {
                log.error("Failed to close connection", e);
            } finally {
                dedicatedConnection = null;
                connectionOwner = false;
            }
        }
    }

    public Optional<T> findById(Object id) throws SQLException {
        log.info("Finding {} by id: {}", entityClass.getSimpleName(), id);

        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ?",
                getTableName(),
                getIdColumn()
        );

        if (cacheEnabled) {
            String cacheKey = entityClass.getName() + ":findById:" + id;
            List<T> cachedResult = queryCache.getIfPresent(cacheKey);
            if (cachedResult != null && !cachedResult.isEmpty()) {
                metricsCollector.recordCacheHit("query");
                log.debug("Cache hit for findById: {}", id);
                return Optional.of(cachedResult.get(0));
            }
            metricsCollector.recordCacheMiss("query");
        }

        log.showSQL(sql, id);
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql, id);
        Instant start = Instant.now();

        Connection conn = acquireConnection();

        try {
            List<T> result = executeQuery(conn, sql, id);

            Duration duration = Duration.between(start, Instant.now());
            queryLogger.logQueryEnd(logEntry, result.size(), null);
            metricsCollector.recordQuery("findById", duration, true);

            if (result.isEmpty()) {
                log.warn("Entity not found by id: {}", id);
                return Optional.empty();
            }

            String[] eagerRelations = getEagerRelationNames();
            if (eagerRelations.length > 0) {
                EagerLoader.batchLoad(result, entityClass, conn, connectionManager, eagerRelations);
            }

            if (cacheEnabled) {
                queryCache.put(entityClass.getName() + ":findById:" + id, result);
            }

            log.info("Entity found by id: {}", id);
            return Optional.of(result.get(0));
        } catch (Exception e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            metricsCollector.recordQuery("findById", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            releaseConnection();
        }
    }

    private String getTableName() {
        return NamingStrategy.getTableName(entityClass);
    }

    private String getIdColumn() {
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return NamingStrategy.getIdColumnName(field);
            }
        }
        throw new MappingException("No @Id field found in " + entityClass.getName(), null);
    }

    @SuppressWarnings("unchecked")
    private T executeInsert(Connection connection, Object entity,
                            Field idField, boolean generatedOnDb) throws SQLException, IllegalAccessException {
        String tableName = getTableName();
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(Transient.class)) continue;

            Object fieldValue = field.get(entity);
            if (fieldValue == null && field.isAnnotationPresent(DefaultValue.class)) {
                DefaultValue defaultValue = field.getAnnotation(DefaultValue.class);
                fieldValue = convertDefaultValue(defaultValue.value(), field.getType());
                field.set(entity, fieldValue);
            }

            if (isRelationshipField(field)) {
                if (field.isAnnotationPresent(ManyToOne.class)) {
                    Object relatedEntity = field.get(entity);
                    if (relatedEntity != null) {
                        Object relatedId = getEntityId(relatedEntity);
                        if (relatedId != null) {
                            String fkColumn = NamingStrategy.getForeignKeyColumn(field);
                            columns.append(fkColumn).append(",");
                            placeholders.append("?,");
                            params.add(relatedId);
                        }
                    }
                } else if (field.isAnnotationPresent(OneToOne.class)) {
                    OneToOne oo = field.getAnnotation(OneToOne.class);
                    if (oo.mappedBy().isEmpty()) {
                        Object relatedEntity = field.get(entity);
                        if (relatedEntity != null) {
                            Object relatedId = getEntityId(relatedEntity);
                            if (relatedId != null) {
                                String fkColumn = NamingStrategy.getOneToOneFkColumn(field);
                                columns.append(fkColumn).append(",");
                                placeholders.append("?,");
                                params.add(relatedId);
                            }
                        }
                    }
                }
                continue;
            }
            if (field.isAnnotationPresent(Id.class) && generatedOnDb) continue;

            String colName = NamingStrategy.getColumnName(field);
            Object value = field.get(entity);

            columns.append(colName).append(",");
            placeholders.append("?,");
            params.add(value);
        }

        if (columns.isEmpty()) {
            String sql = String.format("INSERT INTO %s DEFAULT VALUES", tableName);
            log.showSQL(sql);

            QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
            try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setQueryTimeout(queryTimeout);
                int rowsAffected = stmt.executeUpdate();
                queryLogger.logQueryEnd(logEntry, rowsAffected, null);

                if (rowsAffected > 0 && generatedOnDb) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            Object generatedId = rs.getObject(1);
                            idField.set(entity, generatedId);
                            log.info("Entity saved with DB-generated ID: {}", generatedId);
                        }
                    }
                }
                return (T) entity;
            } catch (SQLException e) {
                queryLogger.logQueryEnd(logEntry, 0, e);
                throw e;
            }
        }

        columns.setLength(columns.length() - 1);
        placeholders.setLength(placeholders.length() - 1);

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);
        log.showSQL(sql, params.toArray());
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql, params.toArray());

        PreparedStatement stmt = null;
        try {
            if (statementCache != null && !externalConnection) {
                stmt = statementCache.getStatement(connection, sql);
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
            } else {
                stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                stmt.setQueryTimeout(queryTimeout);
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
            }

            int rowsAffected = stmt.executeUpdate();
            queryLogger.logQueryEnd(logEntry, rowsAffected, null);

            if (rowsAffected > 0) {
                if (generatedOnDb) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            Object generatedId = rs.getObject(1);
                            idField.set(entity, generatedId);
                            log.info("Entity saved with DB-generated ID: {}", generatedId);
                        }
                    }
                } else {
                    Object manualId = idField.get(entity);
                    log.info("Entity saved with ORM-generated ID: {}", manualId);
                }
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            return (T) entity;
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            throw e;
        } finally {
            if ((statementCache == null || externalConnection) && stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.warn("Failed to close statement", e);
                }
            }
        }
    }

    private Object convertDefaultValue(String value, Class<?> targetType) {
        if (targetType == String.class) return value;
        if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(value);
        if (targetType == long.class || targetType == Long.class) return Long.parseLong(value);
        if (targetType == double.class || targetType == Double.class) return Double.parseDouble(value);
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(value);
        return value;
    }

    private Object getEntityId(Object entity) throws IllegalAccessException {
        if (entity == null) return null;
        Class<?> clazz = entity.getClass();
        for (Field field : getAllFields(clazz)) {
            if (field.isAnnotationPresent(Id.class)) {
                field.setAccessible(true);
                return field.get(entity);
            }
        }
        return null;
    }

    private boolean isRelationshipField(Field field) {
        return field.isAnnotationPresent(OneToOne.class) ||
                field.isAnnotationPresent(OneToMany.class) ||
                field.isAnnotationPresent(ManyToOne.class) ||
                field.isAnnotationPresent(ManyToMany.class);
    }

    private void handleManyToMany(Connection connection, Object entity, Object ownerId) throws SQLException, IllegalAccessException {
        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(ManyToMany.class)) {
                ManyToMany m2m = field.getAnnotation(ManyToMany.class);
                if (m2m.mappedBy().isEmpty()) {
                    JoinTable jt = field.getAnnotation(JoinTable.class);
                    Class<?> targetClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];

                    String joinTableName = NamingStrategy.getJoinTableName(entityClass, targetClass, jt);
                    String ownerCol = NamingStrategy.getJoinColumnName(jt, entityClass, true);
                    String inverseCol = NamingStrategy.getJoinColumnName(jt, targetClass, false);

                    String deleteSql = String.format("DELETE FROM %s WHERE %s = ?", joinTableName, ownerCol);
                    QueryLogEntry deleteLog = queryLogger.logQueryStart(deleteSql, ownerId);
                    try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                        deleteStmt.setQueryTimeout(queryTimeout);
                        deleteStmt.setObject(1, ownerId);
                        int deleted = deleteStmt.executeUpdate();
                        queryLogger.logQueryEnd(deleteLog, deleted, null);
                    } catch (SQLException e) {
                        queryLogger.logQueryEnd(deleteLog, 0, e);
                        throw e;
                    }

                    java.util.Collection<?> relatedItems = (java.util.Collection<?>) field.get(entity);
                    if (relatedItems != null && !relatedItems.isEmpty()) {
                        String insertSql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?)", joinTableName, ownerCol, inverseCol);
                        QueryLogEntry insertLog = queryLogger.logQueryStart(insertSql);
                        try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                            insertStmt.setQueryTimeout(queryTimeout);
                            int count = 0;
                            for (Object item : relatedItems) {
                                Object inverseId = getEntityId(item);
                                if (inverseId != null) {
                                    insertStmt.setObject(1, ownerId);
                                    insertStmt.setObject(2, inverseId);
                                    insertStmt.addBatch();
                                    count++;
                                    if (count >= batchSize) {
                                        insertStmt.executeBatch();
                                        count = 0;
                                    }
                                }
                            }
                            if (count > 0) {
                                insertStmt.executeBatch();
                            }
                            queryLogger.logQueryEnd(insertLog, relatedItems.size(), null);
                        } catch (SQLException e) {
                            queryLogger.logQueryEnd(insertLog, 0, e);
                            throw e;
                        }
                    }
                }
            }
        }
    }

    public T save(Object entity) throws SQLException, IllegalAccessException {
        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("SAVE " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Validator.validate(entity);

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            T result = saveInternal(conn, entity, visited);

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            metricsCollector.recordQuery("save", Duration.between(start, Instant.now()), true);
            return result;
        } catch (SQLException | IllegalAccessException | ValidationException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("save", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    public List<T> saveAll(List<?> entities) throws SQLException, IllegalAccessException {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("SAVE_ALL " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;
        List<T> results = new ArrayList<>();

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Field idField = findIdField();
            idField.setAccessible(true);
            GeneratedValue gen = getGeneratedValue(idField);

            if (gen != null && (gen.strategy() == Strategy.SEQUENCE ||
                    gen.strategy() == Strategy.PATTERN)) {
                for (Object entity : entities) {
                    if (idField.get(entity) == null) {
                        Object generatedId = generateId(conn, gen, idField);
                        idField.set(entity, generatedId);
                    }
                }
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

            List<Field> orderedFields = getOrderedFieldsForBatch();

            String sql = buildBatchInsertSql(orderedFields);
            QueryLogEntry logEntry = queryLogger.logQueryStart(sql);

            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setQueryTimeout(queryTimeout);

                int batchCount = 0;
                for (Object entity : entities) {
                    Validator.validate(entity);

                    int paramIndex = 1;
                    for (Field field : orderedFields) {
                        field.setAccessible(true);
                        Object value = field.get(entity);

                        if (isRelationshipField(field) && value != null) {
                            value = getEntityId(value);
                        }

                        stmt.setObject(paramIndex++, value);
                    }

                    stmt.addBatch();
                    batchCount++;

                    if (batchCount >= batchSize) {
                        stmt.executeBatch();
                        batchCount = 0;
                    }
                }

                if (batchCount > 0) {
                    stmt.executeBatch();
                }

                if (gen != null && gen.strategy() == Strategy.IDENTITY) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        int i = 0;
                        while (rs.next() && i < entities.size()) {
                            Object entity = entities.get(i);
                            Object generatedId = rs.getObject(1);
                            idField.set(entity, generatedId);
                            i++;
                        }
                    }
                }

                queryLogger.logQueryEnd(logEntry, results.size(), null);
            } catch (SQLException e) {
                queryLogger.logQueryEnd(logEntry, 0, e);
                throw e;
            }

            for (Object entity : entities) {
                @SuppressWarnings("unchecked")
                T result = (T) entity;
                results.add(result);
            }

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            metricsCollector.recordQuery("saveAll", Duration.between(start, Instant.now()), true);
            return results;
        } catch (SQLException | IllegalAccessException | ValidationException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("saveAll", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    private Object generateId(Connection connection, GeneratedValue gen, Field idField) throws SQLException {
        ValueSpec.validateStrategyAttributes(gen);
        ValueSpec spec = ValueSpec.parse(gen.value(), gen.strategy());
        String tableName = getTableName();
        String idColumn = getIdColumn();

        return switch (gen.strategy()) {
            case SEQUENCE -> nextSequenceValue(connection, tableName, idColumn, spec.sequenceStart());
            case PATTERN -> {
                if (idField.getType() != String.class) {
                    throw new SQLException("PATTERN strategy requires a String ID field");
                }
                long counter = nextSequenceValue(connection, tableName, idColumn, spec.sequenceStart());
                yield spec.format(counter);
            }
            default -> throw new SQLException("Unsupported strategy for batch: " + gen.strategy());
        };
    }

    private List<Field> getOrderedFieldsForBatch() {
        List<Field> orderedFields = new ArrayList<>();

        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Transient.class)) continue;

            if (field.isAnnotationPresent(Id.class)) {
                GeneratedValue gen = field.getAnnotation(GeneratedValue.class);
                if (gen != null && gen.strategy() == Strategy.IDENTITY) continue;
            }

            if (isRelationshipField(field)) {
                if (field.isAnnotationPresent(ManyToOne.class) ||
                        (field.isAnnotationPresent(OneToOne.class) &&
                                field.getAnnotation(OneToOne.class).mappedBy().isEmpty())) {
                    orderedFields.add(field);
                }
                continue;
            }

            orderedFields.add(field);
        }

        return orderedFields;
    }

    private String buildBatchInsertSql(List<Field> orderedFields) {
        String tableName = getTableName();
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();

        for (Field field : orderedFields) {
            String colName;

            if (field.isAnnotationPresent(ManyToOne.class)) {
                colName = NamingStrategy.getForeignKeyColumn(field);
            } else if (field.isAnnotationPresent(OneToOne.class)) {
                colName = NamingStrategy.getOneToOneFkColumn(field);
            } else {
                colName = NamingStrategy.getColumnName(field);
            }

            columns.append(colName).append(",");
            placeholders.append("?,");
        }

        if (columns.length() > 0) {
            columns.setLength(columns.length() - 1);
            placeholders.setLength(placeholders.length() - 1);
        }

        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);
    }

    private String buildBatchInsertSql() {
        String tableName = getTableName();
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();

        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Transient.class)) continue;
            if (field.isAnnotationPresent(Id.class)) {
                GeneratedValue gen = field.getAnnotation(GeneratedValue.class);
                if (gen != null && gen.strategy() == Strategy.IDENTITY) continue;
            }
            if (isRelationshipField(field)) {
                if (field.isAnnotationPresent(ManyToOne.class) ||
                        (field.isAnnotationPresent(OneToOne.class) &&
                                field.getAnnotation(OneToOne.class).mappedBy().isEmpty())) {
                    String fkColumn = field.isAnnotationPresent(ManyToOne.class)
                            ? NamingStrategy.getForeignKeyColumn(field)
                            : NamingStrategy.getOneToOneFkColumn(field);
                    columns.append(fkColumn).append(",");
                    placeholders.append("?,");
                }
                continue;
            }

            String colName = NamingStrategy.getColumnName(field);
            columns.append(colName).append(",");
            placeholders.append("?,");
        }

        if (columns.length() > 0) {
            columns.setLength(columns.length() - 1);
            placeholders.setLength(placeholders.length() - 1);
        }

        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);
    }

    private Map<Field, Integer> getFieldIndicesForBatch() {
        Map<Field, Integer> indices = new HashMap<>();
        int index = 0;
        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Transient.class)) continue;
            if (field.isAnnotationPresent(Id.class)) {
                GeneratedValue gen = field.getAnnotation(GeneratedValue.class);
                if (gen != null && gen.strategy() == Strategy.IDENTITY) continue;
            }
            if (isRelationshipField(field)) {
                if (field.isAnnotationPresent(ManyToOne.class) ||
                        (field.isAnnotationPresent(OneToOne.class) &&
                                field.getAnnotation(OneToOne.class).mappedBy().isEmpty())) {
                    indices.put(field, index++);
                }
                continue;
            }
            indices.put(field, index++);
        }
        return indices;
    }

    public T saveInternal(Connection connection, Object entity, Set<Object> visited) throws SQLException, IllegalAccessException {
        if (visited.contains(entity)) return (T) entity;
        visited.add(entity);

        log.info("Saving entity: {}", entity.getClass().getSimpleName());

        Field idField = findIdField();
        idField.setAccessible(true);

        GeneratedValue gen = getGeneratedValue(idField);
        Object idValue = idField.get(entity);

        String tableName = getTableName();
        String idColumn = getIdColumn();

        boolean generatedOnDb = isGeneratedOnDb(gen, idValue);

        try {
            new CascadeHandler(connection).handlePrePersist(entity, visited);
        } catch (Exception e) {
            throw new SQLException("Pre-Cascade persist failed", e);
        }

        if (gen != null) {
            ValueSpec.validateStrategyAttributes(gen);
            ValueSpec spec = ValueSpec.parse(gen.value(), gen.strategy());

            switch (gen.strategy()) {
                case SEQUENCE -> {
                    long seqVal = nextSequenceValue(connection, tableName, idColumn, spec.sequenceStart());
                    idField.set(entity, seqVal);
                    idValue = seqVal;
                }
                case PATTERN -> {
                    if (idField.getType() != String.class) {
                        throw new SQLException("PATTERN strategy requires a String ID field");
                    }
                    long counter = nextSequenceValue(connection, tableName, idColumn, spec.sequenceStart());
                    String patternId = spec.format(counter);
                    idField.set(entity, patternId);
                    idValue = patternId;
                }
                case CUSTOM -> {
                    if (idValue == null) throw new SQLException("CUSTOM strategy requires manual ID assignment before saving");
                }
            }
        }

        T result = executeInsert(connection, entity, idField, generatedOnDb);
        Object finalId = idField.get(result);

        try {
            new CascadeHandler(connection).handlePostPersist(result, visited);
        } catch (Exception e) {
            throw new SQLException("Post-Cascade persist failed", e);
        }

        handleManyToMany(connection, result, finalId);

        return result;
    }

    public void update(Object entity) throws SQLException {
        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("UPDATE " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            updateInternal(conn, entity, visited);

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            metricsCollector.recordQuery("update", Duration.between(start, Instant.now()), true);
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("update", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    public void updateAll(List<?> entities) throws SQLException {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("UPDATE_ALL " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            String sql = buildBatchUpdateSql();
            Field idField = findIdField();
            idField.setAccessible(true);

            QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setQueryTimeout(queryTimeout);

                int batchCount = 0;
                for (Object entity : entities) {
                    int paramIndex = 1;

                    for (Field field : getAllFields(entityClass)) {
                        field.setAccessible(true);
                        if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Transient.class)) continue;

                        if (isRelationshipField(field)) {
                            if (field.isAnnotationPresent(ManyToOne.class)) {
                                Object related = field.get(entity);
                                stmt.setObject(paramIndex++, related != null ? getEntityId(related) : null);
                            } else if (field.isAnnotationPresent(OneToOne.class)) {
                                OneToOne oo = field.getAnnotation(OneToOne.class);
                                if (oo.mappedBy().isEmpty()) {
                                    Object related = field.get(entity);
                                    stmt.setObject(paramIndex++, related != null ? getEntityId(related) : null);
                                }
                            }
                            continue;
                        }

                        stmt.setObject(paramIndex++, field.get(entity));
                    }

                    stmt.setObject(paramIndex, idField.get(entity));
                    stmt.addBatch();
                    batchCount++;

                    if (batchCount >= batchSize) {
                        stmt.executeBatch();
                        batchCount = 0;
                    }
                }

                if (batchCount > 0) {
                    stmt.executeBatch();
                }
                queryLogger.logQueryEnd(logEntry, entities.size(), null);
            } catch (IllegalAccessException e) {
                queryLogger.logQueryEnd(logEntry, 0, e);
                throw new SQLException("Batch update failed", e);
            }

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            metricsCollector.recordQuery("updateAll", Duration.between(start, Instant.now()), true);
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("updateAll", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    private String buildBatchUpdateSql() {
        String tableName = getTableName();
        StringBuilder setClause = new StringBuilder();

        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Transient.class)) continue;

            if (isRelationshipField(field)) {
                if (field.isAnnotationPresent(ManyToOne.class)) {
                    String fkColumn = NamingStrategy.getForeignKeyColumn(field);
                    setClause.append(fkColumn).append(" = ?,");
                } else if (field.isAnnotationPresent(OneToOne.class)) {
                    OneToOne oo = field.getAnnotation(OneToOne.class);
                    if (oo.mappedBy().isEmpty()) {
                        String fkColumn = NamingStrategy.getOneToOneFkColumn(field);
                        setClause.append(fkColumn).append(" = ?,");
                    }
                }
                continue;
            }

            String colName = NamingStrategy.getColumnName(field);
            setClause.append(colName).append(" = ?,");
        }

        if (setClause.length() > 0) {
            setClause.setLength(setClause.length() - 1);
        }

        return String.format("UPDATE %s SET %s WHERE %s = ?", tableName, setClause, getIdColumn());
    }

    public void updateInternal(Connection connection, Object entity, Set<Object> visited) throws SQLException {
        if (visited.contains(entity)) return;
        visited.add(entity);

        log.info("Updating entity: {}", entity.getClass().getSimpleName());

        try {
            new CascadeHandler(connection).handleMerge(entity, visited);
        } catch (Exception e) {
            throw new SQLException("Cascade merge failed", e);
        }

        StringBuilder setClause = new StringBuilder();
        List<Object> params = new ArrayList<>();
        Object idValue = null;

        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            try {
                if (field.isAnnotationPresent(Id.class)) {
                    idValue = field.get(entity);
                } else if (field.isAnnotationPresent(Transient.class)) {
                    continue;
                } else if (isRelationshipField(field)) {
                    if (field.isAnnotationPresent(ManyToOne.class)) {
                        Object relatedEntity = field.get(entity);
                        if (relatedEntity != null) {
                            Object relatedId = getEntityId(relatedEntity);
                            if (relatedId != null) {
                                String fkColumn = NamingStrategy.getForeignKeyColumn(field);
                                setClause.append(fkColumn).append(" = ?, ");
                                params.add(relatedId);
                            }
                        }
                    } else if (field.isAnnotationPresent(OneToOne.class)) {
                        OneToOne oo = field.getAnnotation(OneToOne.class);
                        if (oo.mappedBy().isEmpty()) {
                            Object relatedEntity = field.get(entity);
                            if (relatedEntity != null) {
                                Object relatedId = getEntityId(relatedEntity);
                                if (relatedId != null) {
                                    String fkColumn = NamingStrategy.getOneToOneFkColumn(field);
                                    setClause.append(fkColumn).append(" = ?, ");
                                    params.add(relatedId);
                                }
                            }
                        }
                    }
                    continue;
                } else {
                    setClause.append(NamingStrategy.getColumnName(field)).append(" = ?, ");
                    params.add(field.get(entity));
                }
            } catch (IllegalAccessException e) {
                throw new QueryException("Cannot access field " + field.getName(), e);
            }
        }

        if (setClause.length() == 0) {
            throw new QueryException("No fields to update", null);
        }

        setClause.delete(setClause.length() - 2, setClause.length());
        String sql = String.format("UPDATE %s SET %s WHERE %s = ?",
                getTableName(), setClause, getIdColumn());
        params.add(idValue);

        log.showSQL(sql, params.toArray());
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql, params.toArray());

        PreparedStatement stmt = null;
        try {
            if (statementCache != null && !externalConnection) {
                stmt = statementCache.getStatement(connection, sql);
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
            } else {
                stmt = connection.prepareStatement(sql);
                stmt.setQueryTimeout(queryTimeout);
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
            }

            int rows = stmt.executeUpdate();
            queryLogger.logQueryEnd(logEntry, rows, null);

            if (rows == 0) {
                throw new QueryException("Entity not found for update", null);
            }
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            throw e;
        } finally {
            if ((statementCache == null || externalConnection) && stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.warn("Failed to close statement", e);
                }
            }
        }

        try {
            handleManyToManyUpdate(connection, entity, idValue);
        } catch (IllegalAccessException e) {
            throw new SQLException("Error handling many-to-many relationship update", e);
        }
    }

    private void handleManyToManyUpdate(Connection connection, Object entity, Object ownerId) throws SQLException, IllegalAccessException {
        for (Field field : getAllFields(entityClass)) {
            field.setAccessible(true);
            if (!field.isAnnotationPresent(ManyToMany.class)) continue;

            ManyToMany m2m = field.getAnnotation(ManyToMany.class);
            if (!m2m.mappedBy().isEmpty()) continue;

            JoinTable jt = field.getAnnotation(JoinTable.class);
            Class<?> targetClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];

            String joinTableName = NamingStrategy.getJoinTableName(entityClass, targetClass, jt);
            String ownerCol = NamingStrategy.getJoinColumnName(jt, entityClass, true);
            String inverseCol = NamingStrategy.getJoinColumnName(jt, targetClass, false);

            String deleteSql = String.format("DELETE FROM %s WHERE %s = ?", joinTableName, ownerCol);
            QueryLogEntry deleteLog = queryLogger.logQueryStart(deleteSql, ownerId);
            try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                deleteStmt.setQueryTimeout(queryTimeout);
                deleteStmt.setObject(1, ownerId);
                int deleted = deleteStmt.executeUpdate();
                queryLogger.logQueryEnd(deleteLog, deleted, null);
                log.debug("Deleted {} old many-to-many relations for {}", deleted, ownerId);
            } catch (SQLException e) {
                queryLogger.logQueryEnd(deleteLog, 0, e);
                throw e;
            }

            @SuppressWarnings("unchecked")
            java.util.Collection<Object> relatedItems = (java.util.Collection<Object>) field.get(entity);
            if (relatedItems != null && !relatedItems.isEmpty()) {
                String insertSql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?)", joinTableName, ownerCol, inverseCol);
                QueryLogEntry insertLog = queryLogger.logQueryStart(insertSql);
                try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                    insertStmt.setQueryTimeout(queryTimeout);
                    int count = 0;
                    for (Object item : relatedItems) {
                        Object inverseId = getEntityId(item);
                        if (inverseId != null) {
                            insertStmt.setObject(1, ownerId);
                            insertStmt.setObject(2, inverseId);
                            insertStmt.addBatch();
                            count++;
                            if (count >= batchSize) {
                                insertStmt.executeBatch();
                                count = 0;
                            }
                        }
                    }
                    if (count > 0) {
                        insertStmt.executeBatch();
                    }
                    queryLogger.logQueryEnd(insertLog, relatedItems.size(), null);
                    log.debug("Inserted {} new many-to-many relations for {}", relatedItems.size(), ownerId);
                } catch (SQLException e) {
                    queryLogger.logQueryEnd(insertLog, 0, e);
                    throw e;
                }
            }
        }
    }

    public void updateWithManyToMany(Object entity) throws SQLException {
        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("UPDATE_WITH_M2M " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            updateInternal(conn, entity, visited);

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            metricsCollector.recordQuery("updateWithManyToMany", Duration.between(start, Instant.now()), true);
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("updateWithManyToMany", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    public void delete(Object id) throws SQLException {
        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("DELETE " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            deleteInternal(conn, id, visited);

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            metricsCollector.recordQuery("delete", Duration.between(start, Instant.now()), true);
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("delete", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    public void deleteAll(List<?> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        Instant start = Instant.now();
        TransactionLogEntry txLog = queryLogger.logTransactionStart("DELETE_ALL " + entityClass.getSimpleName());

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            String sql = String.format("DELETE FROM %s WHERE %s = ?", getTableName(), getIdColumn());
            QueryLogEntry logEntry = queryLogger.logQueryStart(sql);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setQueryTimeout(queryTimeout);

                int batchCount = 0;
                for (Object id : ids) {
                    stmt.setObject(1, id);
                    stmt.addBatch();
                    batchCount++;

                    if (batchCount >= batchSize) {
                        stmt.executeBatch();
                        batchCount = 0;
                    }
                }

                if (batchCount > 0) {
                    stmt.executeBatch();
                }
                queryLogger.logQueryEnd(logEntry, ids.size(), null);
            } catch (SQLException e) {
                queryLogger.logQueryEnd(logEntry, 0, e);
                throw e;
            }

            if (shouldManageTx) {
                conn.commit();
                queryLogger.logTransactionEnd(txLog, true, null);
            }

            if (cacheEnabled) {
                queryCache.invalidatePattern(entityClass.getName() + ":*");
            }

            metricsCollector.recordQuery("deleteAll", Duration.between(start, Instant.now()), true);
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
                queryLogger.logTransactionEnd(txLog, false, e);
            }
            metricsCollector.recordQuery("deleteAll", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
    }

    public void deleteInternal(Connection connection, Object id, Set<Object> visited) throws SQLException {
        log.info("Deleting by ID: {}", id);

        Optional<T> entityOpt = findByIdInternal(connection, id);
        if (entityOpt.isPresent()) {
            T entity = entityOpt.get();
            if (visited.contains(entity)) return;
            visited.add(entity);

            try {
                new CascadeHandler(connection).handleRemove(entity, visited);
            } catch (Exception e) {
                throw new SQLException("Cascade remove failed", e);
            }
        }

        String sql = String.format("DELETE FROM %s WHERE %s = ?", getTableName(), getIdColumn());
        log.showSQL(sql, id);
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql, id);

        PreparedStatement stmt = null;
        try {
            if (statementCache != null && !externalConnection) {
                stmt = statementCache.getStatement(connection, sql);
                stmt.setObject(1, id);
            } else {
                stmt = connection.prepareStatement(sql);
                stmt.setQueryTimeout(queryTimeout);
                stmt.setObject(1, id);
            }

            int rows = stmt.executeUpdate();
            queryLogger.logQueryEnd(logEntry, rows, null);

            if (rows == 0) {
                throw new EntityNotFoundException("Entity with given ID not found for deletion");
            }
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            throw e;
        } finally {
            if ((statementCache == null || externalConnection) && stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.warn("Failed to close statement", e);
                }
            }
        }
    }

    private Optional<T> findByIdInternal(Connection connection, Object id) throws SQLException {
        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ?",
                getTableName(),
                getIdColumn()
        );
        List<T> result = executeQuery(connection, sql, id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    public List<T> findAll() throws SQLException {
        log.info("Finding all {}", entityClass.getSimpleName());
        String sql = "SELECT * FROM " + getTableName();

        if (cacheEnabled) {
            String cacheKey = entityClass.getName() + ":findAll";
            List<T> cached = queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                metricsCollector.recordCacheHit("query");
                return cached;
            }
            metricsCollector.recordCacheMiss("query");
        }

        Connection conn = acquireConnection();
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
        Instant start = Instant.now();

        try {
            List<T> result = executeQuery(conn, sql);
            queryLogger.logQueryEnd(logEntry, result.size(), null);

            String[] eagerRelations = getEagerRelationNames();
            if (!result.isEmpty() && eagerRelations.length > 0) {
                EagerLoader.batchLoad(result, entityClass, conn, connectionManager, eagerRelations);
            }

            metricsCollector.recordQuery("findAll", Duration.between(start, Instant.now()), true);

            if (cacheEnabled) {
                queryCache.put(entityClass.getName() + ":findAll", result);
            }

            return result;
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            metricsCollector.recordQuery("findAll", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            releaseConnection();
        }
    }

    public List<T> query(String sql, Object... params) throws SQLException {
        validateQuery(sql);
        log.showSQL(sql, params);

        if (cacheEnabled && ALLOWED_QUERY_PATTERN.matcher(sql).matches()) {
            String cacheKey = sql + Arrays.toString(params);
            List<T> cached = queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                metricsCollector.recordCacheHit("query");
                return cached;
            }
            metricsCollector.recordCacheMiss("query");
        }

        Connection conn = acquireConnection();
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql, params);
        Instant start = Instant.now();

        try {
            List<T> result = executeQuery(conn, sql, params);
            queryLogger.logQueryEnd(logEntry, result.size(), null);
            metricsCollector.recordQuery("query", Duration.between(start, Instant.now()), true);

            if (cacheEnabled && ALLOWED_QUERY_PATTERN.matcher(sql).matches()) {
                queryCache.put(sql + Arrays.toString(params), result);
            }

            return result;
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            metricsCollector.recordQuery("query", Duration.between(start, Instant.now()), false);
            throw e;
        } finally {
            releaseConnection();
        }
    }

    private void validateQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new SecurityException("SQL query cannot be null or empty");
        }

        String normalized = sql.replaceAll("'[^']*'", "?").replaceAll("\"[^\"]*\"", "?");

        if (DANGEROUS_PATTERN.matcher(normalized).find()) {
            throw new SecurityException("Query contains potentially dangerous SQL patterns");
        }

        if (!ALLOWED_QUERY_PATTERN.matcher(sql).matches()) {
            throw new SecurityException("Query must be a simple SELECT statement");
        }
    }

    private List<T> executeQuery(Connection connection, String sql, Object... params) throws SQLException {
        log.showSQL(sql, params);
        List<T> result = new ArrayList<>();
        EntityMapper<T> mapper = EntityMapper.getInstance(entityClass, connectionManager);

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            if (statementCache != null && !externalConnection) {
                stmt = statementCache.getStatement(connection, sql);
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        setParameter(stmt, i + 1, params[i]);
                    }
                }
            } else {
                stmt = connection.prepareStatement(sql);
                stmt.setQueryTimeout(queryTimeout);
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        setParameter(stmt, i + 1, params[i]);
                    }
                }
            }

            rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(mapper.map(rs));
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    log.warn("Failed to close result set", e);
                }
            }
            if ((statementCache == null || externalConnection) && stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.warn("Failed to close statement", e);
                }
            }
        }
        return result;
    }

    private void setParameter(PreparedStatement stmt, int index, Object value) throws SQLException {
        if (value instanceof List<?> list) {
            Object[] array = list.toArray();
            stmt.setArray(index, stmt.getConnection().createArrayOf("VARCHAR", array));
        } else if (value instanceof java.util.Collection<?> coll) {
            Object[] array = coll.toArray();
            stmt.setArray(index, stmt.getConnection().createArrayOf("VARCHAR", array));
        } else {
            stmt.setObject(index, value);
        }
    }

    private Field findIdField() {
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) return field;
        }
        throw new MappingException("No @Id field found in " + entityClass.getName(), null);
    }

    private GeneratedValue getGeneratedValue(Field idField) {
        return idField.getAnnotation(GeneratedValue.class);
    }

    private boolean isGeneratedOnDb(GeneratedValue gen, Object currentIdValue) {
        if (gen == null) return false;
        // Only IDENTITY delegates ID generation to the database (SERIAL / AUTO_INCREMENT).
        // SEQUENCE, PATTERN and CUSTOM generate the ID on the ORM side before the INSERT,
        // so they must NOT be treated as DB-generated even when the field is currently null.
        return gen.strategy() == Strategy.IDENTITY;
    }

    private long nextSequenceValue(Connection connection, String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = NamingStrategy.getSequenceName(tableName, idColumn);
        String dbName = connection.getMetaData().getDatabaseProductName().toLowerCase();
        boolean isPostgres = dbName.contains("postgresql");
        boolean isH2 = dbName.contains("h2");

        if (isPostgres) {
            return nextPostgresSequence(connection, seqName, tableName, idColumn, startValue);
        } else if (isH2) {
            return nextH2Sequence(connection, seqName, tableName, idColumn, startValue);
        } else {
            return nextGenericSequence(connection, seqName, startValue);
        }
    }

    private long nextPostgresSequence(Connection connection, String seqName, String tableName, String idColumn, long startValue) throws SQLException {
        String checkSql = "SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequencename = ?";
        boolean exists = false;

        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, seqName);
            try (ResultSet rs = stmt.executeQuery()) {
                exists = rs.next();
            }
        }

        if (!exists) {
            String createSql = String.format(
                    "CREATE SEQUENCE %s START WITH %d OWNED BY %s.%s",
                    seqName, startValue, tableName, idColumn
            );
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(createSql);
            }
        }

        String sql = String.format("SELECT nextval('%s')", seqName);
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                queryLogger.logQueryEnd(logEntry, 1, null);
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            throw e;
        }
        throw new SQLException("Could not increment sequence: " + seqName);
    }

    private long nextH2Sequence(Connection connection, String seqName, String tableName, String idColumn, long startValue) throws SQLException {
        String checkSql = "SELECT 1 FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = ?";
        boolean exists = false;

        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, seqName.toUpperCase());
            try (ResultSet rs = stmt.executeQuery()) {
                exists = rs.next();
            }
        }

        if (!exists) {
            String createSql = String.format(
                    "CREATE SEQUENCE %s START WITH %d",
                    seqName, startValue
            );
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(createSql);
            }
        }

        String sql = String.format("SELECT NEXT VALUE FOR %s", seqName);
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                queryLogger.logQueryEnd(logEntry, 1, null);
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            throw e;
        }
        throw new SQLException("Could not increment sequence: " + seqName);
    }

    private long nextGenericSequence(Connection connection, String seqName, long startValue) throws SQLException {
        String sql = String.format("SELECT NEXT VALUE FOR %s", seqName);
        QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                queryLogger.logQueryEnd(logEntry, 1, null);
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            queryLogger.logQueryEnd(logEntry, 0, e);
            throw e;
        }
        throw new SQLException("Could not increment sequence: " + seqName);
    }

    private String generateStartsWithId(Connection connection, String pattern, ValueSpec spec,
                                        String tableName, String idColumn) throws SQLException {
        String prefix  = (pattern == null || pattern.isEmpty()) ? tableName.toUpperCase() + "_" : pattern;
        long   counter = nextSequenceValue(connection, tableName, idColumn, spec.sequenceStart());
        return spec.isFormatted() ? prefix + spec.format(counter) : prefix + counter;
    }

    public void resetSequence() throws SQLException {
        Field idField = findIdField();
        GeneratedValue gen = getGeneratedValue(idField);

        if (gen != null && (gen.strategy() == Strategy.SEQUENCE || gen.strategy() == Strategy.PATTERN)) {
            String seqName = NamingStrategy.getSequenceName(getTableName(), getIdColumn());
            long startValue = ValueSpec.parse(gen.value(), gen.strategy()).sequenceStart();

            Connection conn = acquireConnection();
            String dbName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            boolean isH2 = dbName.contains("h2");

            try {
                String quotedSeq = "\"" + seqName.replace("\"", "\"\"") + "\"";
                String sql;
                if (isH2) {
                    sql = String.format("ALTER SEQUENCE %s RESTART WITH %d INCREMENT BY 1", quotedSeq, startValue);
                } else {
                    sql = String.format("ALTER SEQUENCE %s RESTART WITH %d", quotedSeq, startValue);
                }

                QueryLogger.QueryLogEntry logEntry = queryLogger.logQueryStart(sql);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                    queryLogger.logQueryEnd(logEntry, 0, null);
                }
            } catch (SQLException e) {
                queryLogger.logQueryEnd(null, 0, e);
                log.warn("Could not reset sequence: {}", e.getMessage());
            } finally {
                releaseConnection();
            }
        }
    }

    public void validateSchema() throws SQLException {
        Connection conn = acquireConnection();
        try {
            DatabaseMetaData metaData = conn.getMetaData();
            String tableName = getTableName();

            try (ResultSet tables = metaData.getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
                if (!tables.next()) {
                    throw new SQLException("Table '" + tableName + "' does not exist");
                }
            }

            Map<String, Integer> existingColumns = new HashMap<>();
            try (ResultSet columns = metaData.getColumns(null, null, tableName.toUpperCase(), null)) {
                while (columns.next()) {
                    String colName = columns.getString("COLUMN_NAME");
                    int colType = columns.getInt("DATA_TYPE");
                    existingColumns.put(colName.toUpperCase(), colType);
                }
            }

            for (Field field : getAllFields(entityClass)) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(Transient.class)) continue;

                String expectedCol = NamingStrategy.getColumnName(field).toUpperCase();
                if (!existingColumns.containsKey(expectedCol)) {
                    log.warn("Column '{}' not found in table '{}'", expectedCol, tableName);
                }
            }

            log.info("Schema validation completed for {}", tableName);
        } finally {
            releaseConnection();
        }
    }

    private Field[] getAllFields(Class<?> clazz) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(java.util.Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ex) {
            log.error("Rollback failed", ex);
        }
    }

    private void resetAutoCommitQuietly(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Failed to restore autoCommit", e);
        }
    }

    private String[] getEagerRelationNames() {
        List<String> names = new ArrayList<>();
        for (Field field : getAllFields(entityClass)) {
            if (field.isAnnotationPresent(OneToMany.class)) {
                if (field.getAnnotation(OneToMany.class).fetch() == FetchType.EAGER) {
                    names.add(field.getName());
                }
            } else if (field.isAnnotationPresent(ManyToOne.class)) {
                if (field.getAnnotation(ManyToOne.class).fetch() != FetchType.LAZY) {
                    names.add(field.getName());
                }
            } else if (field.isAnnotationPresent(OneToOne.class)) {
                if (field.getAnnotation(OneToOne.class).fetch() != FetchType.LAZY) {
                    names.add(field.getName());
                }
            } else if (field.isAnnotationPresent(ManyToMany.class)) {
                if (field.getAnnotation(ManyToMany.class).fetch() == FetchType.EAGER) {
                    names.add(field.getName());
                }
            }
        }
        return names.toArray(new String[0]);
    }

    public QueryCache getQueryCache() {
        return queryCache;
    }

}