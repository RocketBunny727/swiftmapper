package ru.nsu.swiftmapper.core;

import lombok.Getter;
import ru.nsu.swiftmapper.annotations.entity.Column;
import ru.nsu.swiftmapper.annotations.entity.Entity;
import ru.nsu.swiftmapper.annotations.entity.Id;
import ru.nsu.swiftmapper.annotations.entity.Table;
import ru.nsu.swiftmapper.annotations.relationship.*;
import ru.nsu.swiftmapper.logger.SwiftLogger;
import ru.nsu.swiftmapper.proxy.LazyList;
import ru.nsu.swiftmapper.proxy.ProxyFactory;

import java.lang.reflect.*;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class EntityMapper<T> {
    private final Class<T> entityClass;
    private final Map<String, Field> columnToField = new HashMap<>();
    private final Map<String, String> fieldToColumn = new HashMap<>();
    private final Map<String, RelationshipField> relationshipFields = new HashMap<>();
    private final Constructor<T> constructor;
    private final Field idField;
    private final Connection connection;

    private static final SwiftLogger log = SwiftLogger.getLogger(EntityMapper.class);
    private static final Map<Class<?>, EntityMapper<?>> cache = new ConcurrentHashMap<>();

    public EntityMapper(Class<T> entityClass, Connection connection) {
        this.entityClass = entityClass;
        this.connection = connection;

        if (!entityClass.isAnnotationPresent(Entity.class))
            throw new IllegalArgumentException("Class must be @Entity");

        this.constructor = findDefaultConstructor();
        this.idField = findIdField();
        cacheFieldMappings();
        cacheRelationshipFields();

        log.info("EntityMapper initialized for {}", entityClass.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public static <T> EntityMapper<T> getInstance(Class<T> entityClass, Connection connection) {
        return (EntityMapper<T>) cache.computeIfAbsent(entityClass,
                k -> new EntityMapper<>(entityClass, connection));
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
        throw new IllegalStateException("No @Id field in " + entityClass.getName());
    }

    private void cacheFieldMappings() {
        for (Field field : entityClass.getDeclaredFields()) {
            if (isRelationshipField(field)) continue;

            field.setAccessible(true);
            String fieldName = field.getName().toLowerCase();
            String colName = field.isAnnotationPresent(Column.class)
                    ? field.getAnnotation(Column.class).name()
                    : fieldName;
            if (colName.isEmpty()) colName = fieldName;

            columnToField.put(colName.toLowerCase(), field);
            fieldToColumn.put(fieldName, colName);
        }
        log.info("Cached {} field mappings", columnToField.size());
    }

    private void cacheRelationshipFields() {
        for (Field field : entityClass.getDeclaredFields()) {
            field.setAccessible(true);
            RelationshipType type = getRelationshipType(field);
            if (type != null) {
                relationshipFields.put(field.getName(), new RelationshipField(field, type));
            }
        }
        log.info("Cached {} relationship fields", relationshipFields.size());
    }

    private boolean isRelationshipField(Field field) {
        return field.isAnnotationPresent(OneToOne.class) ||
                field.isAnnotationPresent(OneToMany.class) ||
                field.isAnnotationPresent(ManyToOne.class) ||
                field.isAnnotationPresent(ManyToMany.class);
    }

    private RelationshipType getRelationshipType(Field field) {
        if (field.isAnnotationPresent(OneToOne.class)) return RelationshipType.ONE_TO_ONE;
        if (field.isAnnotationPresent(OneToMany.class)) return RelationshipType.ONE_TO_MANY;
        if (field.isAnnotationPresent(ManyToOne.class)) return RelationshipType.MANY_TO_ONE;
        if (field.isAnnotationPresent(ManyToMany.class)) return RelationshipType.MANY_TO_MANY;
        return null;
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
        return map(rs, new HashMap<>());
    }

    private T map(ResultSet rs, Map<Object, Object> visited) throws SQLException {
        try {
            Object id = rs.getObject(getIdColumn());
            if (id == null) return null;

            String cacheKey = entityClass.getName() + ":" + id;
            if (visited.containsKey(cacheKey)) {
                @SuppressWarnings("unchecked")
                T cached = (T) visited.get(cacheKey);
                return cached;
            }

            T entity = constructor.newInstance();
            visited.put(cacheKey, entity);

            ResultSetMetaData meta = rs.getMetaData();

            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String colLabel = meta.getColumnLabel(i).toLowerCase();
                Field field = columnToField.get(colLabel);
                if (field != null) {
                    Object value = rs.getObject(i);
                    field.set(entity, convertValue(value, field.getType()));
                }
            }

            for (RelationshipField relField : relationshipFields.values()) {
                if (shouldFetchEager(relField)) {
                    loadRelationship(entity, relField, rs, visited);
                } else {
                    setLazyProxy(entity, relField);
                }
            }

            return entity;
        } catch (Exception e) {
            throw new SQLException("Mapping error: " + e.getMessage(), e);
        }
    }

    private boolean shouldFetchEager(RelationshipField field) {
        return switch (field.type()) {
            case ONE_TO_ONE -> field.field().getAnnotation(OneToOne.class).fetch() == FetchType.EAGER;
            case MANY_TO_ONE -> field.field().getAnnotation(ManyToOne.class).fetch() == FetchType.EAGER;
            case ONE_TO_MANY -> field.field().getAnnotation(OneToMany.class).fetch() == FetchType.EAGER;
            case MANY_TO_MANY -> field.field().getAnnotation(ManyToMany.class).fetch() == FetchType.EAGER;
        };
    }

    private void loadRelationship(T entity, RelationshipField relField, ResultSet rs,
                                  Map<Object, Object> visited) throws Exception {
        Field field = relField.field();
        Class<?> targetClass = getTargetClass(field);

        switch (relField.type()) {
            case MANY_TO_ONE -> {
                JoinColumn jc = field.getAnnotation(JoinColumn.class);
                String fkColumn = jc != null && !jc.name().isEmpty() ? jc.name()
                        : field.getName().toLowerCase() + "_id";

                Object fkValue = rs.getObject(fkColumn);
                if (fkValue != null) {
                    Object related = fetchById(targetClass, fkValue, visited);
                    field.set(entity, related);
                }
            }

            case ONE_TO_ONE -> {
                OneToOne anno = field.getAnnotation(OneToOne.class);
                Object ownerId = idField.get(entity);

                if (!anno.mappedBy().isEmpty()) {
                    Object related = fetchOneToOneMappedBy(targetClass, anno.mappedBy(),
                            ownerId, visited);
                    field.set(entity, related);
                } else {
                    JoinColumn jc = field.getAnnotation(JoinColumn.class);
                    String fkColumn = jc != null && !jc.name().isEmpty() ? jc.name()
                            : field.getName().toLowerCase() + "_id";
                    Object fkValue = rs.getObject(fkColumn);
                    if (fkValue != null) {
                        Object related = fetchById(targetClass, fkValue, visited);
                        field.set(entity, related);
                    }
                }
            }

            case ONE_TO_MANY -> {
                OneToMany anno = field.getAnnotation(OneToMany.class);
                Class<?> elementType = (Class<?>) ((ParameterizedType) field.getGenericType())
                        .getActualTypeArguments()[0];
                Object ownerId = idField.get(entity);
                List<?> related = fetchOneToMany(elementType, anno.mappedBy(),
                        ownerId, visited);
                field.set(entity, related);
            }

            case MANY_TO_MANY -> {
                ManyToMany anno = field.getAnnotation(ManyToMany.class);
                Class<?> elementType = (Class<?>) ((ParameterizedType) field.getGenericType())
                        .getActualTypeArguments()[0];
                Object ownerId = idField.get(entity);

                if (!anno.mappedBy().isEmpty()) {
                    List<?> related = fetchManyToManyInverse(elementType, anno.mappedBy(),
                            ownerId, visited);
                    field.set(entity, related);
                } else {
                    JoinTable jt = field.getAnnotation(JoinTable.class);
                    List<?> related = fetchManyToMany(elementType, jt,
                            ownerId, visited);
                    field.set(entity, related);
                }
            }
        }
    }

    private Object fetchById(Class<?> targetClass, Object id, Map<Object, Object> visited)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connection);
        String sql = "SELECT * FROM " + targetMapper.getTableName() + " WHERE "
                + targetMapper.getIdColumn() + " = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return targetMapper.map(rs, visited);
            }
        }
        return null;
    }

    private Object fetchOneToOneMappedBy(Class<?> targetClass, String mappedBy,
                                         Object ownerId, Map<Object, Object> visited)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connection);
        String fkColumn = mappedBy.toLowerCase() + "_id";
        String sql = "SELECT * FROM " + targetMapper.getTableName() + " WHERE "
                + fkColumn + " = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return targetMapper.map(rs, visited);
            }
        }
        return null;
    }

    private List<?> fetchOneToMany(Class<?> elementType, String mappedBy,
                                   Object ownerId, Map<Object, Object> visited)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connection);
        String fkColumn = mappedBy.isEmpty() ?
                entityClass.getSimpleName().toLowerCase() + "_id" : mappedBy.toLowerCase() + "_id";
        String sql = "SELECT * FROM " + targetMapper.getTableName() + " WHERE "
                + fkColumn + " = ?";

        List<Object> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(targetMapper.map(rs, visited));
            }
        }
        return result;
    }

    private List<?> fetchManyToMany(Class<?> elementType, JoinTable jt,
                                    Object ownerId, Map<Object, Object> visited)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connection);
        String joinTable = jt.name().isEmpty() ?
                getTableName() + "_" + targetMapper.getTableName() : jt.name();
        String joinCol = jt.joinColumn().isEmpty() ?
                getTableName().toLowerCase() + "_id" : jt.joinColumn();
        String inverseCol = jt.inverseJoinColumn().isEmpty() ?
                targetMapper.getTableName().toLowerCase() + "_id" : jt.inverseJoinColumn();

        String sql = "SELECT t.* FROM " + targetMapper.getTableName() + " t " +
                "JOIN " + joinTable + " j ON t." + targetMapper.getIdColumn() + " = j." + inverseCol +
                " WHERE j." + joinCol + " = ?";

        List<Object> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(targetMapper.map(rs, visited));
            }
        }
        return result;
    }

    private List<?> fetchManyToManyInverse(Class<?> elementType, String mappedBy,
                                           Object ownerId, Map<Object, Object> visited)
            throws SQLException {
        Field mappedField = Arrays.stream(elementType.getDeclaredFields())
                .filter(f -> f.getName().equals(mappedBy))
                .findFirst()
                .orElseThrow();

        JoinTable jt = mappedField.getAnnotation(JoinTable.class);
        return fetchManyToMany(elementType, jt, ownerId, visited);
    }

    private void setLazyProxy(T entity, RelationshipField relField) {
        Field field = relField.field();
        Class<?> targetClass = getTargetClass(field);

        try {
            Object ownerId = idField.get(entity);
            if (ownerId == null) {
                return;
            }

            switch (relField.type()) {
                case MANY_TO_ONE, ONE_TO_ONE -> {
                    JoinColumn jc = field.getAnnotation(JoinColumn.class);
                    String fkColumn = jc != null && !jc.name().isEmpty() ? jc.name()
                            : field.getName().toLowerCase() + "_id";

                    Runnable loader = () -> {
                        try {
                            Object fkValue = fetchFkValue(ownerId, fkColumn);
                            if (fkValue != null) {
                                Object loaded = fetchById(targetClass, fkValue, new HashMap<>());
                                field.set(entity, loaded);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Lazy load failed for " + field.getName(), e);
                        }
                    };

                    try {
                        Object proxy = ProxyFactory.createEntityProxy(targetClass, loader);
                        field.set(entity, proxy);
                    } catch (Exception e) {
                        log.warn("Failed to create proxy for {}, using null", targetClass.getSimpleName());
                    }
                }

                case ONE_TO_MANY, MANY_TO_MANY -> {
                    OneToMany oneToMany = field.getAnnotation(OneToMany.class);
                    ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);

                    Runnable loader = () -> {
                        try {
                            List<?> loaded;
                            if (relField.type() == RelationshipType.ONE_TO_MANY) {
                                loaded = fetchOneToMany(targetClass,
                                        oneToMany != null ? oneToMany.mappedBy() : "",
                                        ownerId, new HashMap<>());
                            } else {
                                if (manyToMany != null && !manyToMany.mappedBy().isEmpty()) {
                                    loaded = fetchManyToManyInverse(targetClass, manyToMany.mappedBy(),
                                            ownerId, new HashMap<>());
                                } else {
                                    JoinTable jt = field.getAnnotation(JoinTable.class);
                                    loaded = fetchManyToMany(targetClass, jt, ownerId, new HashMap<>());
                                }
                            }
                            @SuppressWarnings("unchecked")
                            LazyList<Object> lazyList = (LazyList<Object>) field.get(entity);
                            lazyList.setDelegate((List<Object>) loaded);
                        } catch (Exception e) {
                            throw new RuntimeException("Lazy load failed for " + field.getName(), e);
                        }
                    };

                    LazyList<?> lazyList = ProxyFactory.createLazyList(loader);
                    field.set(entity, lazyList);
                }
            }

            log.debug("Set lazy proxy for field {}.{}", entityClass.getSimpleName(), field.getName());
        } catch (Exception e) {
            log.error("Failed to set lazy proxy for {}.{}", e, entityClass.getSimpleName(), field.getName());
        }
    }

    private Object fetchFkValue(Object ownerId, String fkColumn) throws SQLException {
        String sql = "SELECT " + fkColumn + " FROM " + getTableName() + " WHERE " + getIdColumn() + " = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getObject(1);
            }
        }
        return null;
    }

    private Class<?> getTargetClass(Field field) {
        Class<?> type = field.getType();
        if (Collection.class.isAssignableFrom(type)) {
            ParameterizedType pt = (ParameterizedType) field.getGenericType();
            return (Class<?>) pt.getActualTypeArguments()[0];
        }
        return type;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;

        if (targetType == boolean.class || targetType == Boolean.class)
            return value;
        if (targetType == int.class || targetType == Integer.class)
            return ((Number)value).intValue();
        if (targetType == long.class || targetType == Long.class)
            return ((Number)value).longValue();
        if (targetType == double.class || targetType == Double.class)
            return ((Number)value).doubleValue();
        if (targetType == LocalDateTime.class && value instanceof Timestamp)
            return ((Timestamp)value).toLocalDateTime();
        if (targetType == LocalDate.class && value instanceof Date)
            return ((Date)value).toLocalDate();

        return value;
    }
}