package com.rocketbunny.swiftmapper.core;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.*;
import com.rocketbunny.swiftmapper.cache.StatementCache;
import com.rocketbunny.swiftmapper.exception.MappingException;
import com.rocketbunny.swiftmapper.exception.QueryException;
import com.rocketbunny.swiftmapper.utils.naming.NamingStrategy;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Session<T> {
    private final ConnectionManager connectionManager;
    private final Class<T> entityClass;
    private final SwiftLogger log = SwiftLogger.getLogger(Session.class);
    private Connection dedicatedConnection;
    private boolean externalConnection = false;
    private StatementCache statementCache;

    public Session(ConnectionManager connectionManager, Class<T> entityClass) {
        this.connectionManager = connectionManager;
        this.entityClass = entityClass;
        log.info("Session created for {}", entityClass.getSimpleName());
    }

    public Session(Connection connection, Class<T> entityClass) {
        this.connectionManager = null;
        this.entityClass = entityClass;
        this.dedicatedConnection = connection;
        this.externalConnection = true;
        log.info("Session created for {} with external connection", entityClass.getSimpleName());
    }

    public void setStatementCache(StatementCache cache) {
        this.statementCache = cache;
    }

    public Optional<T> findById(Object id) throws SQLException {
        log.info("Finding {} by id: {}", entityClass.getSimpleName(), id);

        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ?",
                getTableName(),
                getIdColumn()
        );
        log.debug("SQL: {}", sql);

        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            List<T> result = executeQuery(conn, sql, id);
            if (result.isEmpty()) {
                log.warn("Entity not found by id: {}", id);
                return Optional.empty();
            }
            log.info("Entity found by id: {}", id);
            return Optional.of(result.get(0));
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
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

    private T executeInsert(Connection connection, Object entity,
                            Field idField, boolean generatedOnDb) throws SQLException, IllegalAccessException {
        String tableName = getTableName();
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Field field : entityClass.getDeclaredFields()) {
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
                }
                else if (field.isAnnotationPresent(OneToOne.class)) {
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
            if (statementCache != null) {
                stmt = statementCache.getStatement(connection, sql);
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
            } else {
                stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
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
            if (statementCache == null && stmt != null) {
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
        for (Field field : clazz.getDeclaredFields()) {
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

    @SuppressWarnings("unchecked")
    public T save(Object entity) throws SQLException, IllegalAccessException {
        log.info("Saving entity: {}", entity.getClass().getSimpleName());

        Field idField = findIdField();
        idField.setAccessible(true);

        GeneratedValue gen = getGeneratedValue(idField);
        Object idValue = idField.get(entity);

        String tableName = getTableName();
        String idColumn = getIdColumn();

        if (gen != null) {
            switch (gen.strategy()) {
                case SEQUENCE:
                    long seqVal = nextSequenceValue(tableName, idColumn, gen.startValue());
                    idField.set(entity, seqVal);
                    idValue = seqVal;
                    break;

                case PATTERN:
                    if (idField.getType() != String.class) {
                        throw new SQLException("PATTERN strategy requires String ID field");
                    }
                    String patternId = generateStartsWithId(gen.pattern(), gen.startValue(), tableName);
                    idField.set(entity, patternId);
                    idValue = patternId;
                    break;

                case ALPHA:
                    long alphaId = nextSequenceValue(tableName, idColumn, gen.startValue());
                    idField.set(entity, alphaId);
                    idValue = alphaId;
                    break;

                case CUSTOM:
                    if (idValue == null) throw new SQLException("CUSTOM strategy requires manual ID");
                    break;

                default: break;
            }
        }

        boolean generatedOnDb = isGeneratedOnDb(gen, idValue);

        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            conn.setAutoCommit(false);
            T result = executeInsert(conn, entity, idField, generatedOnDb);
            conn.commit();
            return result;
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                log.error("Rollback failed", ex);
            }
            throw e;
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
        }
    }

    public void update(Object entity) throws SQLException {
        log.info("Updating entity: {}", entity.getClass().getSimpleName());

        StringBuilder setClause = new StringBuilder();
        List<Object> params = new ArrayList<>();
        Object idValue = null;

        for (Field field : entityClass.getDeclaredFields()) {
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
                log.error("Cannot access field {}", e, field.getName());
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

        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            conn.setAutoCommit(false);

            PreparedStatement stmt = null;
            try {
                if (statementCache != null) {
                    stmt = statementCache.getStatement(conn, sql);
                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }
                } else {
                    stmt = conn.prepareStatement(sql);
                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }
                }

                int rows = stmt.executeUpdate();
                log.info("Rows updated: {}", rows);
                if (rows == 0) {
                    log.warn("No entity found to update");
                    throw new QueryException("Entity not found for update", null);
                }
            } finally {
                if (statementCache == null && stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        log.warn("Failed to close statement", e);
                    }
                }
            }

            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                log.error("Rollback failed", ex);
            }
            throw e;
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
        }
    }

    public void delete(Object id) throws SQLException {
        log.info("Deleting by ID: {}", id);

        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            conn.setAutoCommit(false);

            T entity = findById(id).orElse(null);
            if (entity != null) {
                handleCascadeDelete(conn, entity);
            }

            String sql = String.format("DELETE FROM %s WHERE %s = ?",
                    getTableName(), getIdColumn());
            log.debug("SQL: {}", sql);

            PreparedStatement stmt = null;
            try {
                if (statementCache != null) {
                    stmt = statementCache.getStatement(conn, sql);
                    stmt.setObject(1, id);
                } else {
                    stmt = conn.prepareStatement(sql);
                    stmt.setObject(1, id);
                }

                int rows = stmt.executeUpdate();
                log.info("Rows deleted: {}", rows);
                if (rows == 0) {
                    log.warn("No entity found to delete");
                    throw new QueryException("Entity not found for delete", null);
                }
            } finally {
                if (statementCache == null && stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        log.warn("Failed to close statement", e);
                    }
                }
            }

            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                log.error("Rollback failed", ex);
            }
            throw e;
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
        }
    }

    private void handleCascadeDelete(Connection conn, Object entity) throws SQLException {
        for (Field field : entityClass.getDeclaredFields()) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(OneToMany.class)) {
                OneToMany anno = field.getAnnotation(OneToMany.class);
                if (shouldCascade(anno.cascade(), CascadeType.REMOVE)) {
                    try {
                        java.util.Collection<?> related = (java.util.Collection<?>) field.get(entity);
                        if (related != null) {
                            for (Object item : related) {
                                deleteRelated(conn, item);
                            }
                        }
                    } catch (IllegalAccessException e) {
                        throw new SQLException("Cannot access field", e);
                    }
                }
            } else if (field.isAnnotationPresent(OneToOne.class)) {
                OneToOne anno = field.getAnnotation(OneToOne.class);
                if (shouldCascade(anno.cascade(), CascadeType.REMOVE)) {
                    try {
                        Object related = field.get(entity);
                        if (related != null) {
                            deleteRelated(conn, related);
                        }
                    } catch (IllegalAccessException e) {
                        throw new SQLException("Cannot access field", e);
                    }
                }
            }
        }
    }

    private boolean shouldCascade(CascadeType[] types, CascadeType target) {
        for (CascadeType type : types) {
            if (type == CascadeType.ALL || type == target) {
                return true;
            }
        }
        return false;
    }

    private void deleteRelated(Connection conn, Object entity) throws SQLException, IllegalAccessException {
        Class<?> clazz = entity.getClass();
        Object relatedId = getEntityId(entity);
        if (relatedId != null) {
            Session<?> session = new Session<>(conn, clazz);
            session.delete(relatedId);
        }
    }

    public List<T> findAll() throws SQLException {
        log.info("Finding all {}", entityClass.getSimpleName());
        String sql = "SELECT * FROM " + getTableName();
        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            return executeQuery(conn, sql);
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
        }
    }

    public List<T> query(String sql, Object... params) throws SQLException {
        log.debug("Custom query: {}", sql);
        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            return executeQuery(conn, sql, params);
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
        }
    }

    private List<T> executeQuery(Connection connection, String sql, Object... params) throws SQLException {
        log.debug("Executing query: {}", sql);
        List<T> result = new ArrayList<>();
        EntityMapper<T> mapper = EntityMapper.getInstance(entityClass, connectionManager);

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            if (statementCache != null) {
                stmt = statementCache.getStatement(connection, sql);
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        stmt.setObject(i + 1, params[i]);
                    }
                }
            } else {
                stmt = connection.prepareStatement(sql);
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
            if (statementCache == null && stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.warn("Failed to close statement", e);
                }
            }
        }

        log.info("Query returned {} rows", result.size());
        return result;
    }

    private Field findIdField() {
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
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

    private long nextSequenceValue(String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = NamingStrategy.getSequenceName(tableName, idColumn);
        String checkSql = "SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequencename = ?";
        boolean exists = false;

        Connection conn = getConnection();
        boolean shouldClose = !externalConnection;

        try {
            String dbName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            boolean isPostgres = dbName.contains("postgresql");

            if (isPostgres) {
                try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
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
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(createSql);
                    log.info("Created sequence {} on demand", seqName);
                }
            }

            String sql = isPostgres
                    ? String.format("SELECT nextval('%s')", seqName)
                    : String.format("SELECT NEXT VALUE FOR %s", seqName);

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            throw new SQLException("Could not increment sequence: " + seqName);
        } finally {
            if (shouldClose && conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.error("Failed to close connection", e);
                }
            }
        }
    }

    private String generateStartsWithId(String pattern, long startsWith, String tableName) throws SQLException {
        String p = (pattern == null || pattern.isEmpty()) ? tableName.toUpperCase() + "_" : pattern;
        long nextNumber = nextSequenceValue(tableName, getIdColumn(), startsWith);
        return p + nextNumber;
    }

    public void resetSequence() throws SQLException {
        Field idField = findIdField();
        GeneratedValue gen = getGeneratedValue(idField);

        if (gen != null) {
            String seqName = NamingStrategy.getSequenceName(getTableName(), getIdColumn());
            long startValue = gen.startValue();

            log.info("Resetting sequence {} to {}", seqName, startValue);
            String sql = String.format("ALTER SEQUENCE %s RESTART WITH %d", seqName, startValue);

            Connection conn = getConnection();
            boolean shouldClose = !externalConnection;

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                log.warn("Could not reset sequence (maybe it doesn't exist yet): {}", e.getMessage());
            } finally {
                if (shouldClose && conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        log.error("Failed to close connection", e);
                    }
                }
            }
        }
    }

    private Connection getConnection() throws SQLException {
        if (dedicatedConnection != null) {
            return dedicatedConnection;
        }
        if (connectionManager != null) {
            return connectionManager.getConnection();
        }
        throw new SQLException("No connection source available");
    }
}