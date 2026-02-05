package ru.nsu.swiftmapper.core;

import ru.nsu.swiftmapper.annotations.entity.*;
import ru.nsu.swiftmapper.annotations.relationship.*;
import ru.nsu.swiftmapper.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Session<T> {
    private final Connection connection;
    private final EntityMapper<T> mapper;
    private final SwiftLogger log = SwiftLogger.getLogger(Session.class);

    public Session(Connection connection, Class<T> entityClass) {
        this.connection = connection;
        this.mapper = new EntityMapper<>(entityClass, connection);
        log.info("Session created for {}", entityClass.getSimpleName());
    }

    public Optional<T> findById(Object id) throws SQLException {
        log.info("Finding {} by id: {}", mapper.getEntityClass().getSimpleName(), id);

        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ?",
                mapper.getTableName(),
                mapper.getIdColumn()
        );
        log.debug("SQL: {}", sql);

        List<T> result = executeQuery(sql, id);
        if (result.isEmpty()) {
            log.warn("Entity not found by id: {}", id);
            return Optional.empty();
        }

        log.info("Entity found by id: {}", id);
        return Optional.of(result.get(0));
    }

    private T executeInsert(Object entity, Field idField, boolean generatedOnDb) throws SQLException, IllegalAccessException {
        String tableName = mapper.getTableName();
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Field field : mapper.getEntityClass().getDeclaredFields()) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(Transient.class)) continue;
            if (isRelationshipField(field)) {
                if (field.isAnnotationPresent(ManyToOne.class)) {
                    Object relatedEntity = field.get(entity);
                    if (relatedEntity != null) {
                        Object relatedId = getEntityId(relatedEntity);
                        if (relatedId != null) {
                            JoinColumn jc = field.getAnnotation(JoinColumn.class);
                            String fkColumn = jc != null && !jc.name().isEmpty() ?
                                    jc.name() : field.getName().toLowerCase() + "_id";

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
                                JoinColumn jc = field.getAnnotation(JoinColumn.class);
                                String fkColumn = jc != null && !jc.name().isEmpty() ?
                                        jc.name() : field.getName().toLowerCase() + "_id";

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

            String colName = mapper.getColumnName(field.getName());
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

        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
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

        Field idField = mapper.getIdField();
        idField.setAccessible(true);

        GeneratedValue gen = getGeneratedValue(idField);
        Object idValue = idField.get(entity);

        String tableName = mapper.getTableName();
        String idColumn = mapper.getIdColumn();

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
        return executeInsert(entity, idField, generatedOnDb);
    }

    public void update(Object entity) throws SQLException {
        log.info("Updating entity: {}", entity.getClass().getSimpleName());

        StringBuilder setClause = new StringBuilder();
        List<Object> params = new ArrayList<>();
        Object idValue = null;

        for (Field field : mapper.getEntityClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                if (field.isAnnotationPresent(Id.class)) {
                    idValue = field.get(entity);
                } else {
                    setClause.append(mapper.getColumnName(field.getName())).append(" = ?, ");
                    params.add(field.get(entity));
                }
            } catch (IllegalAccessException e) {
                log.error("Cannot access field {}", e, field.getName());
                throw new SQLException("Cannot access field " + field.getName(), e);
            }
        }

        setClause.delete(setClause.length() - 2, setClause.length());
        String sql = String.format("UPDATE %s SET %s WHERE %s = ?",
                mapper.getTableName(), setClause, mapper.getIdColumn());
        params.add(idValue);

        log.debug("SQL: {}", sql);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            int rows = stmt.executeUpdate();
            log.info("Rows updated: {}", rows);
            if (rows == 0) {
                log.warn("No entity found to update");
                throw new SQLException("Entity not found");
            }
        }
    }

    public void delete(Object id) throws SQLException {
        log.info("Deleting by ID: {}", id);
        String sql = String.format("DELETE FROM %s WHERE %s = ?",
                mapper.getTableName(), mapper.getIdColumn());
        log.debug("SQL: {}", sql);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, id);
            int rows = stmt.executeUpdate();
            log.info("Rows deleted: {}", rows);
            if (rows == 0) {
                log.warn("No entity found to delete");
                throw new SQLException("Entity not found");
            }
        }
    }

    public List<T> findAll() throws SQLException {
        log.info("Finding all {}", mapper.getEntityClass().getSimpleName());
        String sql = "SELECT * FROM " + mapper.getTableName();
        return executeQuery(sql);
    }

    public List<T> query(String sql, Object... params) throws SQLException {
        log.debug("Custom query: {}", sql);
        return executeQuery(sql, params);
    }

    private List<T> executeQuery(String sql, Object... params) throws SQLException {
        log.debug("Executing query: {}", sql);
        List<T> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
            }
        }
        log.info("Query returned {} rows", result.size());
        return result;
    }

    private GeneratedValue getGeneratedValue(Field idField) {
        return idField.getAnnotation(GeneratedValue.class);
    }

    private boolean isGeneratedOnDb(GeneratedValue gen, Object currentIdValue) {
        if (gen == null) return false;
        return gen.strategy() == Strategy.IDENTITY || (gen.strategy() == Strategy.AUTO && currentIdValue == null);
    }

    private long nextSequenceValue(String tableName, String idColumn) throws SQLException {
        String seqName = tableName + "_" + idColumn + "_seq";
        String sql = "SELECT nextval(?)";

        log.debug("Fetching next sequence value from {}", seqName);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, seqName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        throw new SQLException("Cannot get next value for sequence " + seqName);
    }

    private String generateStartsWithId(String pattern, long startsWith, String tableName) throws SQLException {
        String p = (pattern == null || pattern.isEmpty()) ? tableName.toUpperCase() + "_" : pattern;
        long nextNumber = nextSequenceValue(tableName, mapper.getIdColumn(), startsWith);
        return p + nextNumber;
    }

    private long generateAlphaId(long startsWith, String tableName) throws SQLException {
        String seqName = tableName + "_" + mapper.getIdColumn() + "_custom_seq";

        return nextSequenceValue(tableName, mapper.getIdColumn(), startsWith);
    }

    private void ensureSequenceExists(String seqName, String tableName, String columnName, long startValue) throws SQLException {
        String createSeq = String.format("CREATE SEQUENCE IF NOT EXISTS %s START WITH %d", seqName, startValue);
        String alterSeq = String.format("ALTER SEQUENCE %s OWNED BY %s.%s", seqName, tableName, columnName);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createSeq);
            stmt.execute(alterSeq);
        }
    }

    public void resetSequence() throws SQLException {
        Field idField = mapper.getIdField();
        GeneratedValue gen = getGeneratedValue(idField);

        if (gen != null) {
            String seqName = (mapper.getTableName() + "_" + mapper.getIdColumn() + "_seq").toLowerCase();
            long startValue = gen.startValue();

            log.info("Resetting sequence {} to {}", seqName, startValue);
            String sql = String.format("ALTER SEQUENCE %s RESTART WITH %d", seqName, startValue);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                log.warn("Could not reset sequence (maybe it doesn't exist yet): {}", e.getMessage());
            }
        }
    }

    private long nextSequenceValue(String tableName, String idColumn, long startValue) throws SQLException {
        String seqName = (tableName + "_" + idColumn + "_seq").toLowerCase();

        String createSql = String.format(
                "CREATE SEQUENCE IF NOT EXISTS %s START WITH %d; " +
                        "ALTER SEQUENCE %s OWNED BY %s.%s",
                seqName, startValue, seqName, tableName, idColumn
        );

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createSql);
        }

        String sql = String.format("SELECT nextval('%s')", seqName);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new SQLException("Could not increment sequence: " + seqName);
    }
}
