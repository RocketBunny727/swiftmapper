package ru.nsu.swiftmapper.core;

import ru.nsu.swiftmapper.annotations.*;

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
        this.mapper = new EntityMapper<>(entityClass);
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


    @SuppressWarnings("unchecked")
    public T save(Object entity) throws SQLException, IllegalAccessException {
        log.info("Saving entity: {}", entity.getClass().getSimpleName());

        Field idField = mapper.getIdField();
        idField.setAccessible(true);

        Object idValue;
        GeneratedValue gen;
        try {
            idValue = idField.get(entity);
            gen = getGeneratedValue(idField);
        } catch (IllegalAccessException e) {
            log.error("Cannot access ID field", e);
            throw new SQLException("Cannot access ID field", e);
        }

        String tableName = mapper.getTableName();
        String idColumn = mapper.getIdColumn();

        if (gen != null) {
            switch (gen.strategy()) {
                case SEQUENCE:
                    long seqVal = nextSequenceValue(tableName, idColumn);
                    idField.set(entity, seqVal);
                    idValue = seqVal;
                    log.info("SEQUENCE ID: {}", seqVal);
                    break;

                case CUSTOM:
                    if (idValue == null) {
                        throw new SQLException("CUSTOM strategy requires manual ID");
                    }
                    log.info("CUSTOM ID provided: {}", idValue);
                    break;

                case PATTERN:
                    if (idField.getType() != String.class) {
                        throw new SQLException("STARTS_WITH_PATTERN requires String ID field");
                    }
                    String patternId = generateStartsWithId(gen.pattern(), gen.startValue(), tableName);
                    try {
                        idField.set(entity, patternId);
                    } catch (IllegalAccessException e) {
                        throw new SQLException("Cannot set pattern ID", e);
                    }
                    idValue = patternId;
                    log.info("STARTS_WITH_PATTERN ID: {}", patternId);
                    break;

                case ALPHA:
                    long alphaId = generateAlphaId(gen.startValue(), tableName);
                    idField.set(entity, alphaId);
                    idValue = alphaId;
                    log.info("ALPHA ID: {}", alphaId);
                    break;

                case AUTO:
                    if (idValue != null) {
                        log.info("AUTO with manual ID: {}", idValue);
                    }
                    break;

                case IDENTITY:
                    break;
            }
        }

        boolean generatedOnDb = isGeneratedOnDb(gen, idValue);

        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Field field : mapper.getEntityClass().getDeclaredFields()) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(Id.class)) {
                if (generatedOnDb) {
                    continue;
                } else {
                    columns.append(mapper.getColumnName(field.getName())).append(',');
                    placeholders.append("?,");
                    try {
                        params.add(field.get(entity));
                    } catch (IllegalAccessException e) {
                        log.error("Cannot access ID field", e);
                        throw new SQLException("Cannot access ID field", e);
                    }
                    continue;
                }
            }

            try {
                columns.append(mapper.getColumnName(field.getName())).append(',');
                placeholders.append("?,");
                params.add(field.get(entity));
            } catch (IllegalAccessException e) {
                log.error("Cannot access field {}", e, field.getName());
                throw new SQLException("Cannot access field " + field.getName(), e);
            }
        }

        if (columns.length() == 0) {
            throw new SQLException("No columns to insert for " + tableName);
        }
        columns.deleteCharAt(columns.length() - 1);
        placeholders.deleteCharAt(placeholders.length() - 1);

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);
        log.debug("SQL: {}", sql);

        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            int rows = stmt.executeUpdate();
            log.info("Rows affected: {}", rows);

            if (rows > 0 && generatedOnDb) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        long dbId = rs.getLong(1);
                        idField.set(entity, dbId);
                        log.info("IDENTITY/AUTO ID from DB: {}", dbId);
                    }
                }
            }
            return (T) entity;
        }
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

        switch (gen.strategy()) {
            case IDENTITY:
                return true;
            case AUTO:
                return currentIdValue == null;
            default:
                return false;
        }
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
        if (pattern.isEmpty()) {
            pattern = tableName.toUpperCase() + "_";
        }

        String seqName = tableName + "_" + mapper.getIdColumn() + "_custom_seq";
        String sql = String.format("SELECT nextval('%s')", seqName);

        long nextNumber;
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                nextNumber = rs.getLong(1);
            } else {
                throw new SQLException("Cannot get next value from sequence " + seqName);
            }
        }

        String id = pattern + nextNumber;
        log.info("Generated STARTS_WITH_PATTERN: '{}' from sequence {} (next={})", id, seqName, nextNumber);
        return id;
    }

    private long generateAlphaId(long startsWith, String tableName) throws SQLException {
        String seqName = tableName + "_" + mapper.getIdColumn() + "_custom_seq";
        String sql = String.format("SELECT nextval('%s')", seqName);

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new SQLException("Cannot get next value from sequence " + seqName);
    }

}

