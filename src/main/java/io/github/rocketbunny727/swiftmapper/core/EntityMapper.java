package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Entity;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Id;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.*;
import io.github.rocketbunny727.swiftmapper.dialect.SqlDialect;
import io.github.rocketbunny727.swiftmapper.dialect.SqlRenderer;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;
import io.github.rocketbunny727.swiftmapper.exception.MappingException;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import io.github.rocketbunny727.swiftmapper.proxy.LazyList;
import io.github.rocketbunny727.swiftmapper.proxy.ProxyFactory;

import java.lang.reflect.*;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class EntityMapper<T> {
    private final Class<T> entityClass;
    private final Map<String, Field> columnToField;
    private final Map<String, String> fieldToColumn;
    private final Map<String, RelationshipField> relationshipFields;
    private final Constructor<T> constructor;
    private final Field idField;
    private final ConnectionManager connectionManager;
    private final SqlRenderer sqlRenderer;

    private static final SwiftLogger log = SwiftLogger.getLogger(EntityMapper.class);
    private static final Map<MapperKey, EntityMapper<?>> cache = new ConcurrentHashMap<>();
    private static final int MAX_DEPTH = 10;
    private static final int MAX_CACHE_SIZE = 100;

    public EntityMapper(Class<T> entityClass, ConnectionManager connectionManager) {
        this.entityClass = entityClass;
        this.connectionManager = connectionManager;
        this.sqlRenderer = new SqlRenderer(connectionManager != null
                ? connectionManager.getDialect()
                : SqlDialect.GENERIC);

        if (!entityClass.isAnnotationPresent(Entity.class))
            throw new IllegalArgumentException("Class must be @Entity");

        this.constructor = findDefaultConstructor();
        this.idField = findIdField();
        this.columnToField = new HashMap<>();
        this.fieldToColumn = new HashMap<>();
        this.relationshipFields = new HashMap<>();

        cacheFieldMappings();
        cacheRelationshipFields();

        log.info("EntityMapper initialized for {}", entityClass.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public static <T> EntityMapper<T> getInstance(Class<T> entityClass, ConnectionManager connectionManager) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.clear();
            log.warn("EntityMapper cache cleared due to size limit");
        }
        SqlDialect dialect = connectionManager != null ? connectionManager.getDialect() : SqlDialect.GENERIC;
        MapperKey key = new MapperKey(entityClass, dialect);
        return (EntityMapper<T>) cache.computeIfAbsent(key,
                k -> new EntityMapper<>(entityClass, connectionManager));
    }

    private record MapperKey(Class<?> entityClass, SqlDialect dialect) {}

    public Class<T> getEntityClass() {
        return entityClass;
    }

    public Map<String, RelationshipField> getRelationshipFields() {
        return Collections.unmodifiableMap(relationshipFields);
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
        List<Field> fields = getAllFields(entityClass);
        for (Field field : fields) {
            if (isRelationshipField(field)) continue;

            field.setAccessible(true);
            String fieldName = field.getName();
            String colName = NamingStrategy.getColumnName(field);

            columnToField.put(colName, field);
            fieldToColumn.put(fieldName, colName);
        }
        log.info("Cached {} field mappings", columnToField.size());
    }

    private void cacheRelationshipFields() {
        List<Field> fields = getAllFields(entityClass);
        for (Field field : fields) {
            field.setAccessible(true);
            RelationshipType type = getRelationshipType(field);
            if (type != null) {
                relationshipFields.put(field.getName(), new RelationshipField(field, type));
            }
        }
        log.info("Cached {} relationship fields", relationshipFields.size());
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
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
        return NamingStrategy.getTableName(entityClass);
    }

    public String getIdColumn() {
        return NamingStrategy.getIdColumnName(idField);
    }

    public String getColumnName(String fieldName) {
        return fieldToColumn.getOrDefault(fieldName, fieldName);
    }

    public T map(ResultSet rs) throws SQLException {
        return map(rs, new HashMap<>(), 0);
    }

    private T map(ResultSet rs, Map<String, Object> visited, int depth) throws SQLException {
        if (depth > MAX_DEPTH) {
            throw new MappingException("Maximum mapping depth exceeded (possible circular reference)", null);
        }

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
                String colLabel = NamingStrategy.normalizeColumnLabel(meta.getColumnLabel(i));
                Field field = columnToField.get(colLabel);
                if (field != null) {
                    Object value = rs.getObject(i);
                    field.set(entity, convertValue(value, field.getType()));
                }
            }

            for (RelationshipField relField : relationshipFields.values()) {
                setLazyProxy(entity, relField, id);
            }

            return entity;
        } catch (Exception e) {
            throw new MappingException("Mapping error for " + entityClass.getSimpleName(), e);
        }
    }

    private void setLazyProxy(T entity, RelationshipField relField, Object ownerId) {
        Field field = relField.field();
        Class<?> targetClass = NamingStrategy.getTargetClass(field);

        try {
            switch (relField.type()) {
                case MANY_TO_ONE -> {
                    String fkColumn = NamingStrategy.getForeignKeyColumn(field);

                    final Object capturedOwnerId = ownerId;
                    final String capturedFkColumn = fkColumn;
                    final String capturedTableName = getTableName();

                    Callable<Object> loader = () -> {
                        try (Connection conn = connectionManager.getConnection()) {
                            Object fkValue = fetchFkValue(capturedOwnerId, capturedFkColumn, capturedTableName, conn);
                            if (fkValue != null) {
                                return fetchByIdWithConnection(targetClass, fkValue, new HashMap<>(), 0, conn);
                            }
                        }
                        return null;
                    };

                    Object proxy = ProxyFactory.createEntityProxy(targetClass, loader);
                    field.set(entity, proxy);
                }

                case ONE_TO_ONE -> {
                    OneToOne anno = field.getAnnotation(OneToOne.class);

                    if (!anno.mappedBy().isEmpty()) {
                        final Object capturedOwnerId = ownerId;
                        final String capturedMappedBy = anno.mappedBy();

                        Callable<Object> loader = () -> {
                            try (Connection conn = connectionManager.getConnection()) {
                                return fetchOneToOneMappedByWithConnection(targetClass, capturedMappedBy,
                                        capturedOwnerId, new HashMap<>(), 0, conn);
                            }
                        };

                        Object proxy = ProxyFactory.createEntityProxy(targetClass, loader);
                        field.set(entity, proxy);
                    } else {
                        String fkColumn = NamingStrategy.getOneToOneFkColumn(field);

                        final Object capturedOwnerId = ownerId;
                        final String capturedFkColumn = fkColumn;
                        final String capturedTableName = getTableName();

                        Callable<Object> loader = () -> {
                            try (Connection conn = connectionManager.getConnection()) {
                                Object fkValue = fetchFkValue(capturedOwnerId, capturedFkColumn, capturedTableName, conn);
                                if (fkValue != null) {
                                    return fetchByIdWithConnection(targetClass, fkValue, new HashMap<>(), 0, conn);
                                }
                            }
                            return null;
                        };

                        Object proxy = ProxyFactory.createEntityProxy(targetClass, loader);
                        field.set(entity, proxy);
                    }
                }

                case ONE_TO_MANY, MANY_TO_MANY -> {
                    OneToMany oneToMany = field.getAnnotation(OneToMany.class);
                    ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);

                    final Object capturedOwnerId = ownerId;

                    Runnable loader = () -> {
                        try (Connection conn = connectionManager.getConnection()) {
                            List<?> loaded;
                            if (relField.type() == RelationshipType.ONE_TO_MANY) {
                                loaded = fetchOneToManyWithConnection(targetClass,
                                        oneToMany != null ? oneToMany.mappedBy() : "",
                                        capturedOwnerId, new HashMap<>(), 0, conn);
                            } else {
                                if (manyToMany != null && !manyToMany.mappedBy().isEmpty()) {
                                    loaded = fetchManyToManyInverseWithConnection(targetClass, manyToMany.mappedBy(),
                                            capturedOwnerId, new HashMap<>(), 0, conn);
                                } else {
                                    JoinTable jt = field.getAnnotation(JoinTable.class);
                                    loaded = fetchManyToManyWithConnection(targetClass, jt, capturedOwnerId, new HashMap<>(), 0, conn);
                                }
                            }
                            @SuppressWarnings("unchecked")
                            LazyList<Object> lazyList = (LazyList<Object>) field.get(entity);
                            if (lazyList != null) {
                                lazyList.setDelegate((List<Object>) loaded);
                            }
                        } catch (Exception e) {
                            throw new MappingException("Lazy load failed for " + field.getName(), e);
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

    private Object fetchById(Class<?> targetClass, Object id, Map<String, Object> visited, int depth) throws SQLException {
        try (Connection conn = connectionManager.getConnection()) {
            return fetchByIdWithConnection(targetClass, id, visited, depth, conn);
        }
    }

    private Object fetchByIdWithConnection(Class<?> targetClass, Object id, Map<String, Object> visited, int depth, Connection conn)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connectionManager);
        String sql = sqlRenderer.selectById(targetMapper.getTableName(), targetMapper.getIdColumn());

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(30);
            stmt.setObject(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return targetMapper.map(rs, visited, depth);
            }
        }
        return null;
    }

    private Object fetchOneToOneMappedBy(Class<?> targetClass, String mappedBy,
                                         Object ownerId, Map<String, Object> visited, int depth) throws SQLException {
        try (Connection conn = connectionManager.getConnection()) {
            return fetchOneToOneMappedByWithConnection(targetClass, mappedBy, ownerId, visited, depth, conn);
        }
    }

    private Object fetchOneToOneMappedByWithConnection(Class<?> targetClass, String mappedBy,
                                                       Object ownerId, Map<String, Object> visited, int depth, Connection conn)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connectionManager);
        String fkColumn = NamingStrategy.getOneToManyFkColumn(entityClass, mappedBy);
        String sql = sqlRenderer.selectAllWhereEquals(targetMapper.getTableName(), fkColumn);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(30);
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return targetMapper.map(rs, visited, depth);
            }
        }
        return null;
    }

    private List<?> fetchOneToMany(Class<?> elementType, String mappedBy,
                                   Object ownerId, Map<String, Object> visited, int depth) throws SQLException {
        try (Connection conn = connectionManager.getConnection()) {
            return fetchOneToManyWithConnection(elementType, mappedBy, ownerId, visited, depth, conn);
        }
    }

    private List<?> fetchOneToManyWithConnection(Class<?> elementType, String mappedBy,
                                                 Object ownerId, Map<String, Object> visited, int depth, Connection conn)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connectionManager);
        String fkColumn = NamingStrategy.getOneToManyFkColumn(entityClass, mappedBy);
        String sql = sqlRenderer.selectAllWhereEquals(targetMapper.getTableName(), fkColumn);

        List<Object> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(30);
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(targetMapper.map(rs, visited, depth));
            }
        }
        return result;
    }

    private List<?> fetchManyToMany(Class<?> elementType, JoinTable jt,
                                    Object ownerId, Map<String, Object> visited, int depth) throws SQLException {
        try (Connection conn = connectionManager.getConnection()) {
            return fetchManyToManyWithConnection(elementType, jt, ownerId, visited, depth, conn);
        }
    }

    private List<?> fetchManyToManyWithConnection(Class<?> elementType, JoinTable jt,
                                                  Object ownerId, Map<String, Object> visited, int depth, Connection conn)
            throws SQLException {
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connectionManager);
        String joinTable = NamingStrategy.getJoinTableName(entityClass, elementType, jt);
        String joinCol = NamingStrategy.getJoinColumnName(jt, entityClass, true);
        String inverseCol = NamingStrategy.getJoinColumnName(jt, elementType, false);

        String sql = "SELECT t.* FROM " + sqlRenderer.table(targetMapper.getTableName()) + " t "
                + "JOIN " + sqlRenderer.table(joinTable) + " j ON "
                + sqlRenderer.qualify("t", targetMapper.getIdColumn()) + " = "
                + sqlRenderer.qualify("j", inverseCol)
                + " WHERE " + sqlRenderer.qualify("j", joinCol) + " = ?";

        List<Object> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(30);
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(targetMapper.map(rs, visited, depth));
            }
        }
        return result;
    }

    private List<?> fetchManyToManyInverse(Class<?> elementType, String mappedBy,
                                           Object ownerId, Map<String, Object> visited, int depth) throws SQLException {
        try (Connection conn = connectionManager.getConnection()) {
            return fetchManyToManyInverseWithConnection(elementType, mappedBy, ownerId, visited, depth, conn);
        }
    }

    private List<?> fetchManyToManyInverseWithConnection(Class<?> elementType, String mappedBy,
                                                         Object ownerId, Map<String, Object> visited, int depth, Connection conn)
            throws SQLException {
        Field mappedField = Arrays.stream(elementType.getDeclaredFields())
                .filter(f -> f.getName().equals(mappedBy))
                .findFirst()
                .orElseThrow();

        JoinTable jt = mappedField.getAnnotation(JoinTable.class);
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connectionManager);
        String joinTable = NamingStrategy.getJoinTableName(elementType, entityClass, jt);
        String ownerColumn = NamingStrategy.getJoinColumnName(jt, entityClass, false);
        String targetColumn = NamingStrategy.getJoinColumnName(jt, elementType, true);

        String sql = "SELECT t.* FROM " + sqlRenderer.table(targetMapper.getTableName()) + " t "
                + "JOIN " + sqlRenderer.table(joinTable) + " j ON "
                + sqlRenderer.qualify("t", targetMapper.getIdColumn()) + " = "
                + sqlRenderer.qualify("j", targetColumn)
                + " WHERE " + sqlRenderer.qualify("j", ownerColumn) + " = ?";

        List<Object> result = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(30);
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(targetMapper.map(rs, visited, depth));
            }
        }
        return result;
    }

    private Object fetchFkValue(Object ownerId, String fkColumn, String tableName, Connection conn) throws SQLException {
        String sql = sqlRenderer.selectColumnWhereEquals(tableName, fkColumn, getIdColumn());
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(30);
            stmt.setObject(1, ownerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getObject(1);
            }
        }
        return null;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;

        if (targetType.isInstance(value)) return value;

        if (targetType == String.class) return value.toString();

        Class<?> wrapper = getWrapperClass(targetType);

        if (value instanceof String s && Number.class.isAssignableFrom(wrapper)) {
            if (wrapper == Long.class) return Long.parseLong(s);
            if (wrapper == Integer.class) return Integer.parseInt(s);
            if (wrapper == Double.class) return Double.parseDouble(s);
            if (wrapper == Float.class) return Float.parseFloat(s);
        }

        if (value instanceof Number n) {
            if (wrapper == Integer.class) return n.intValue();
            if (wrapper == Long.class) return n.longValue();
            if (wrapper == Double.class) return n.doubleValue();
            if (wrapper == Float.class) return n.floatValue();
            if (wrapper == Short.class) return n.shortValue();
            if (wrapper == Boolean.class) return n.intValue() != 0;
        }

        if (targetType == LocalDateTime.class && value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (targetType == LocalDate.class && value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (targetType == LocalTime.class && value instanceof java.sql.Time t) {
            return t.toLocalTime();
        }

        if (wrapper == Boolean.class && value instanceof Boolean b) {
            return b;
        }

        return value;
    }

    private Class<?> getWrapperClass(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        return type;
    }
}
