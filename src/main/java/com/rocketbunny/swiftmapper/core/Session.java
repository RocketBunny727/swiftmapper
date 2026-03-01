package com.rocketbunny.swiftmapper.core;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.*;
import com.rocketbunny.swiftmapper.cache.StatementCache;
import com.rocketbunny.swiftmapper.cascade.CascadeHandler;
import com.rocketbunny.swiftmapper.exception.MappingException;
import com.rocketbunny.swiftmapper.exception.QueryException;
import com.rocketbunny.swiftmapper.utils.naming.NamingStrategy;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

public class Session<T> {
    private final ConnectionManager connectionManager;
    private final Class<T> entityClass;
    private final SwiftLogger log = SwiftLogger.getLogger(Session.class);
    private Connection dedicatedConnection;
    private boolean externalConnection = false;
    private boolean connectionOwner = false;
    private boolean externalTransaction = false;
    private StatementCache statementCache;
    private int queryTimeout = 30;

    public Session(ConnectionManager connectionManager, Class<T> entityClass) {
        this.connectionManager = connectionManager;
        this.entityClass = entityClass;
        this.statementCache = connectionManager.getStatementCache();
        log.info("Session created for {}", entityClass.getSimpleName());
    }

    public Session(Connection connection, Class<T> entityClass) {
        this.connectionManager = null;
        this.entityClass = entityClass;
        this.dedicatedConnection = connection;
        this.externalConnection = true;
        this.statementCache = null;
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

    private Connection acquireConnection() throws SQLException {
        if (dedicatedConnection != null) {
            return dedicatedConnection;
        }
        if (connectionManager != null) {
            dedicatedConnection = connectionManager.getConnection();
            connectionOwner = true;
            return dedicatedConnection;
        }
        throw new SQLException("No connection source available");
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
        log.debug("SQL: {}", sql);

        Connection conn = acquireConnection();

        try {
            List<T> result = executeQuery(conn, sql, id);
            if (result.isEmpty()) {
                log.warn("Entity not found by id: {}", id);
                return Optional.empty();
            }
            log.info("Entity found by id: {}", id);
            return Optional.of(result.get(0));
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
            log.debug("Executing SQL: {}", sql);

            try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setQueryTimeout(queryTimeout);
                int rowsAffected = stmt.executeUpdate();
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
            }
        }

        columns.setLength(columns.length() - 1);
        placeholders.setLength(placeholders.length() - 1);

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);
        log.debug("Executing SQL: {}", sql);

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

            return (T) entity;
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
                    try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                        deleteStmt.setQueryTimeout(queryTimeout);
                        deleteStmt.setObject(1, ownerId);
                        deleteStmt.executeUpdate();
                    }

                    java.util.Collection<?> relatedItems = (java.util.Collection<?>) field.get(entity);
                    if (relatedItems != null && !relatedItems.isEmpty()) {
                        String insertSql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?)", joinTableName, ownerCol, inverseCol);
                        try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                            insertStmt.setQueryTimeout(queryTimeout);
                            for (Object item : relatedItems) {
                                Object inverseId = getEntityId(item);
                                if (inverseId != null) {
                                    insertStmt.setObject(1, ownerId);
                                    insertStmt.setObject(2, inverseId);
                                    insertStmt.addBatch();
                                }
                            }
                            insertStmt.executeBatch();
                        }
                    }
                }
            }
        }
    }

    public T save(Object entity) throws SQLException, IllegalAccessException {
        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            T result = saveInternal(conn, entity, visited);

            if (shouldManageTx) {
                conn.commit();
            }
            return result;
        } catch (SQLException | IllegalAccessException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
            }
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

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;
        List<T> results = new ArrayList<>();

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

            for (Object entity : entities) {
                @SuppressWarnings("unchecked")
                T result = saveInternal(conn, entity, visited);
                results.add(result);
            }

            if (shouldManageTx) {
                conn.commit();
            }
            return results;
        } catch (SQLException | IllegalAccessException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
            }
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
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

        if (gen != null) {
            switch (gen.strategy()) {
                case SEQUENCE -> {
                    long seqVal = nextSequenceValue(connection, tableName, idColumn, gen.startValue());
                    idField.set(entity, seqVal);
                    idValue = seqVal;
                }
                case PATTERN -> {
                    if (idField.getType() != String.class) {
                        throw new SQLException("PATTERN strategy requires String ID field");
                    }
                    String patternId = generateStartsWithId(connection, gen.pattern(), gen.startValue(), tableName, idColumn);
                    idField.set(entity, patternId);
                    idValue = patternId;
                }
                case ALPHA -> {
                    long alphaId = nextSequenceValue(connection, tableName, idColumn, gen.startValue());
                    idField.set(entity, alphaId);
                    idValue = alphaId;
                }
                case CUSTOM -> {
                    if (idValue == null) throw new SQLException("CUSTOM strategy requires manual ID");
                }
            }
        }

        boolean generatedOnDb = isGeneratedOnDb(gen, idValue);

        try {
            new CascadeHandler(connection).handlePrePersist(entity, visited);
        } catch (Exception e) {
            throw new SQLException("Pre-Cascade persist failed", e);
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
            }
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
            }
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

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

            for (Object entity : entities) {
                updateInternal(conn, entity, visited);
            }

            if (shouldManageTx) {
                conn.commit();
            }
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
            }
            throw e;
        } finally {
            if (shouldManageTx) {
                resetAutoCommitQuietly(conn);
            }
            releaseConnection();
        }
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

        log.debug("SQL: {}", sql);

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
            if (rows == 0) {
                throw new QueryException("Entity not found for update", null);
            }
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
            handleManyToMany(connection, entity, idValue);
        } catch (IllegalAccessException e) {
            throw new SQLException("Error handling many-to-many relationship update", e);
        }
    }

    public void delete(Object id) throws SQLException {
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
            }
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
            }
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

        Connection conn = acquireConnection();
        boolean shouldManageTx = connectionOwner && !externalConnection && !externalTransaction;

        try {
            if (shouldManageTx) {
                conn.setAutoCommit(false);
            }

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

            for (Object id : ids) {
                deleteInternal(conn, id, visited);
            }

            if (shouldManageTx) {
                conn.commit();
            }
        } catch (SQLException e) {
            if (shouldManageTx) {
                rollbackQuietly(conn);
            }
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
        log.debug("SQL: {}", sql);

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
            if (rows == 0) {
                throw new QueryException("Entity not found for delete", null);
            }
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
        Connection conn = acquireConnection();

        try {
            return executeQuery(conn, sql);
        } finally {
            releaseConnection();
        }
    }

    public List<T> query(String sql, Object... params) throws SQLException {
        log.debug("Custom query: {}", sql);
        Connection conn = acquireConnection();

        try {
            return executeQuery(conn, sql, params);
        } finally {
            releaseConnection();
        }
    }

    private List<T> executeQuery(Connection connection, String sql, Object... params) throws SQLException {
        log.debug("Executing query: {}", sql);
        List<T> result = new ArrayList<>();
        EntityMapper<T> mapper = EntityMapper.getInstance(entityClass, connectionManager);

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            if (statementCache != null && !externalConnection) {
                stmt = statementCache.getStatement(connection, sql);
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        stmt.setObject(i + 1, params[i]);
                    }
                }
            } else {
                stmt = connection.prepareStatement(sql);
                stmt.setQueryTimeout(queryTimeout);
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        stmt.setObject(i + 1, params[i]);
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
        return gen.strategy() == Strategy.IDENTITY || (gen.strategy() == Strategy.AUTO && currentIdValue == null);
    }

    private long nextSequenceValue(Connection connection, String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = NamingStrategy.getSequenceName(tableName, idColumn);
        String checkSql = "SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequencename = ?";
        boolean exists = false;

        String dbName = connection.getMetaData().getDatabaseProductName().toLowerCase();
        boolean isPostgres = dbName.contains("postgresql");

        if (isPostgres) {
            try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
                stmt.setString(1, seqName);
                try (ResultSet rs = stmt.executeQuery()) {
                    exists = rs.next();
                }
            }
        } else {
            exists = true;
        }

        if (!exists && isPostgres) {
            String createSql = String.format(
                    "CREATE SEQUENCE %s START WITH %d OWNED BY %s.%s",
                    seqName, startValue, tableName, idColumn
            );
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(createSql);
            }
        }

        String sql = isPostgres
                ? String.format("SELECT nextval('%s')", seqName)
                : String.format("SELECT NEXT VALUE FOR %s", seqName);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new SQLException("Could not increment sequence: " + seqName);
    }

    private String generateStartsWithId(Connection connection, String pattern, long startsWith, String tableName, String idColumn) throws SQLException {
        String p = (pattern == null || pattern.isEmpty()) ? tableName.toUpperCase() + "_" : pattern;
        long nextNumber = nextSequenceValue(connection, tableName, idColumn, startsWith);
        return p + nextNumber;
    }

    public void resetSequence() throws SQLException {
        Field idField = findIdField();
        GeneratedValue gen = getGeneratedValue(idField);

        if (gen != null) {
            String seqName = NamingStrategy.getSequenceName(getTableName(), getIdColumn());
            long startValue = gen.startValue();

            String sql = String.format("ALTER SEQUENCE %s RESTART WITH %d", seqName, startValue);

            Connection conn = acquireConnection();

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                log.warn("Could not reset sequence: {}", e.getMessage());
            } finally {
                releaseConnection();
            }
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
}