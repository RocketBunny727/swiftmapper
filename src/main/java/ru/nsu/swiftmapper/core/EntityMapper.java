package ru.nsu.swiftmapper.core;

import lombok.Getter;
import ru.nsu.swiftmapper.annotations.*;

import ru.nsu.swiftmapper.logger.SwiftLogger;

import java.lang.reflect.*;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;

@Getter
public class EntityMapper<T> {
    private final Class<T> entityClass;
    private final Map<String, Field> columnToField = new HashMap<>();
    private final Map<String, String> fieldToColumn = new HashMap<>();
    private final Constructor<T> constructor;
    private final Field idField;

    private static final SwiftLogger log = SwiftLogger.getLogger(EntityMapper.class);

    public EntityMapper(Class<T> entityClass) {
        this.entityClass = entityClass;
        if (!entityClass.isAnnotationPresent(Entity.class))
            throw new IllegalArgumentException("Class must be @Entity");

        this.constructor = findDefaultConstructor();
        this.idField = findIdField();
        cacheFieldMappings();
        log.info("EntityMapper initialized for {}", entityClass.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private Constructor<T> findDefaultConstructor() {
        try {
            Constructor<T> ctor = (Constructor<T>) entityClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No default constructor in " + entityClass);
        }
    }

    private Field findIdField() {
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("No @Id field");
    }

    private void cacheFieldMappings() {
        for (Field field : entityClass.getDeclaredFields()) {
            field.setAccessible(true);
            String fieldName = field.getName().toLowerCase();
            String colName = field.isAnnotationPresent(Column.class)
                    ? field.getAnnotation(Column.class).name()
                    : fieldName;
            columnToField.put(colName.toLowerCase(), field);
            fieldToColumn.put(fieldName, colName);
        }
        log.info("Cached {} field mappings", columnToField.size());
    }

    public String getTableName() {
        Table table = entityClass.getAnnotation(Table.class);
        return table != null && !table.name().isEmpty() ? table.name()
                : entityClass.getSimpleName().toLowerCase();
    }

    public String getIdColumn() {
        return idField.isAnnotationPresent(Column.class)
                ? idField.getAnnotation(Column.class).name()
                : idField.getName();
    }

    public String getColumnName(String fieldName) {
        return fieldToColumn.getOrDefault(fieldName.toLowerCase(), fieldName);
    }

    public T map(ResultSet rs) throws SQLException {
        try {
            T entity = constructor.newInstance();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String colLabel = meta.getColumnLabel(i).toLowerCase();
                Field field = columnToField.get(colLabel);
                if (field != null) {
                    Object value = rs.getObject(i);
                    field.set(entity, convertValue(value, field.getType()));
                }
            }
            return entity;
        } catch (Exception e) {
            throw new SQLException("Mapping error: " + e.getMessage(), e);
        }
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;

        if (targetType == boolean.class || targetType == Boolean.class)
            return value;
        if (targetType == int.class || targetType == Integer.class)
            return ((Number)value).intValue();
        if (targetType == long.class || targetType == Long.class)
            return ((Number)value).longValue();
        if (targetType == LocalDateTime.class && value instanceof Timestamp)
            return ((Timestamp)value).toLocalDateTime();
        if (targetType == LocalDate.class && value instanceof Date)
            return ((Date)value).toLocalDate();

        return value;
    }
}
