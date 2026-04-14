package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Id;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.*;
import io.github.rocketbunny727.swiftmapper.exception.MappingException;
import io.github.rocketbunny727.swiftmapper.proxy.LazyList;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class EagerLoader {

    private static final SwiftLogger log = SwiftLogger.getLogger(EagerLoader.class);

    private static final int IN_CLAUSE_CHUNK_SIZE = 999;

    public static <T> void batchLoad(List<T> entities,
                                     Class<T> entityClass,
                                     Connection conn,
                                     ConnectionManager connManager,
                                     String... relations) throws SQLException {

        if (entities == null || entities.isEmpty() || relations == null || relations.length == 0) {
            return;
        }

        List<Object> ownerIds = collectIds(entities, entityClass);
        if (ownerIds.isEmpty()) return;

        for (String relation : relations) {
            Field field = findField(entityClass, relation);
            if (field == null) {
                log.warn("EagerLoader: relation '{}' not found in {}", relation, entityClass.getSimpleName());
                continue;
            }
            field.setAccessible(true);
            loadOneRelation(entities, entityClass, ownerIds, field, conn, connManager);
        }
    }

    private static <T> void loadOneRelation(List<T> entities,
                                            Class<T> entityClass,
                                            List<Object> ownerIds,
                                            Field field,
                                            Connection conn,
                                            ConnectionManager connManager) throws SQLException {
        if (field.isAnnotationPresent(OneToMany.class)) {
            batchLoadOneToMany(entities, entityClass, ownerIds, field, conn, connManager);

        } else if (field.isAnnotationPresent(ManyToOne.class)) {
            batchLoadManyToOne(entities, entityClass, ownerIds, field, conn, connManager);

        } else if (field.isAnnotationPresent(OneToOne.class)) {
            batchLoadOneToOne(entities, entityClass, ownerIds, field, conn, connManager);

        } else if (field.isAnnotationPresent(ManyToMany.class)) {
            batchLoadManyToMany(entities, entityClass, ownerIds, field, conn, connManager);

        } else {
            log.warn("EagerLoader: field '{}' is not a relationship field, skipping", field.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void batchLoadOneToMany(List<T> entities,
                                               Class<T> entityClass,
                                               List<Object> ownerIds,
                                               Field field,
                                               Connection conn,
                                               ConnectionManager connManager) throws SQLException {

        OneToMany anno = field.getAnnotation(OneToMany.class);
        Class<?> elementType = getCollectionElementType(field);
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connManager);
        String fkColumn = NamingStrategy.getOneToManyFkColumn(entityClass, anno.mappedBy());

        Map<Object, List<Object>> grouped = new HashMap<>();
        for (List<Object> chunk : partition(ownerIds)) {
            String sql = "SELECT * FROM " + targetMapper.getTableName()
                    + " WHERE " + fkColumn + " IN (" + placeholders(chunk.size()) + ")";

            log.debug("EagerLoader [OneToMany] batch SQL: {}", sql);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) stmt.setObject(i + 1, chunk.get(i));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object child  = targetMapper.map(rs);
                        Object fkVal  = rs.getObject(fkColumn);
                        grouped.computeIfAbsent(normalizeId(fkVal), k -> new ArrayList<>()).add(child);
                    }
                }
            }
        }

        Field idField = findIdField(entityClass);
        for (T entity : entities) {
            try {
                Object id      = idField.get(entity);
                List<Object> related = grouped.getOrDefault(normalizeId(id), Collections.emptyList());
                injectIntoField(entity, field, related);
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot inject OneToMany into " + field.getName(), e);
            }
        }

        log.info("EagerLoader [OneToMany] '{}': loaded {} child records for {} parents",
                field.getName(), grouped.values().stream().mapToInt(List::size).sum(), entities.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> void batchLoadManyToOne(List<T> entities,
                                               Class<T> entityClass,
                                               List<Object> ownerIds,
                                               Field field,
                                               Connection conn,
                                               ConnectionManager connManager) throws SQLException {
        Class<?> targetClass = field.getType();
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connManager);
        String fkColumn = NamingStrategy.getForeignKeyColumn(field);
        String tableName = NamingStrategy.getTableName(entityClass);

        String fkSql = "SELECT " + getIdColumnName(entityClass) + ", " + fkColumn
                + " FROM " + tableName
                + " WHERE " + getIdColumnName(entityClass) + " IN (" + placeholders(ownerIds.size()) + ")";

        Map<Object, Object> ownerToFk = new LinkedHashMap<>();
        log.debug("EagerLoader [ManyToOne] FK-fetch SQL: {}", fkSql);
        try (PreparedStatement stmt = conn.prepareStatement(fkSql)) {
            for (int i = 0; i < ownerIds.size(); i++) stmt.setObject(i + 1, ownerIds.get(i));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Object ownerId = rs.getObject(1);
                    Object fkVal   = rs.getObject(2);
                    if (fkVal != null) ownerToFk.put(normalizeId(ownerId), normalizeId(fkVal));
                }
            }
        }

        if (ownerToFk.isEmpty()) return;

        List<Object> uniqueFkIds = new ArrayList<>(new LinkedHashSet<>(ownerToFk.values()));
        Map<Object, Object> fkToTarget = new HashMap<>();

        for (List<Object> chunk : partition(uniqueFkIds)) {
            String sql = "SELECT * FROM " + targetMapper.getTableName()
                    + " WHERE " + targetMapper.getIdColumn() + " IN (" + placeholders(chunk.size()) + ")";

            log.debug("EagerLoader [ManyToOne] batch SQL: {}", sql);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) stmt.setObject(i + 1, chunk.get(i));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object target   = targetMapper.map(rs);
                        Object targetId = getIdValue(target, targetClass);
                        fkToTarget.put(normalizeId(targetId), target);
                    }
                }
            }
        }

        Field idField = findIdField(entityClass);
        for (T entity : entities) {
            try {
                Object ownerId = normalizeId(idField.get(entity));
                Object fkVal   = ownerToFk.get(ownerId);
                if (fkVal != null) {
                    Object target = fkToTarget.get(fkVal);
                    if (target != null) field.set(entity, target);
                }
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot inject ManyToOne into " + field.getName(), e);
            }
        }

        log.info("EagerLoader [ManyToOne] '{}': resolved {} unique FK targets for {} parents",
                field.getName(), fkToTarget.size(), entities.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> void batchLoadOneToOne(List<T> entities,
                                              Class<T> entityClass,
                                              List<Object> ownerIds,
                                              Field field,
                                              Connection conn,
                                              ConnectionManager connManager) throws SQLException {
        OneToOne anno = field.getAnnotation(OneToOne.class);
        Class<?> targetClass = field.getType();
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connManager);

        if (!anno.mappedBy().isEmpty()) {
            String fkColumn = NamingStrategy.getOneToManyFkColumn(entityClass, anno.mappedBy());
            Map<Object, Object> fkToTarget = new HashMap<>();

            for (List<Object> chunk : partition(ownerIds)) {
                String sql = "SELECT * FROM " + targetMapper.getTableName()
                        + " WHERE " + fkColumn + " IN (" + placeholders(chunk.size()) + ")";

                log.debug("EagerLoader [OneToOne mappedBy] batch SQL: {}", sql);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) stmt.setObject(i + 1, chunk.get(i));
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Object target = targetMapper.map(rs);
                            Object fkVal  = rs.getObject(fkColumn);
                            fkToTarget.put(normalizeId(fkVal), target);
                        }
                    }
                }
            }

            Field idField = findIdField(entityClass);
            for (T entity : entities) {
                try {
                    Object id = normalizeId(idField.get(entity));
                    Object target = fkToTarget.get(id);
                    if (target != null) field.set(entity, target);
                } catch (IllegalAccessException e) {
                    throw new MappingException("EagerLoader: cannot inject OneToOne(mappedBy) into " + field.getName(), e);
                }
            }

        } else {
            String fkColumn = NamingStrategy.getOneToOneFkColumn(field);
            String tableName = NamingStrategy.getTableName(entityClass);

            String fkSql = "SELECT " + getIdColumnName(entityClass) + ", " + fkColumn
                    + " FROM " + tableName
                    + " WHERE " + getIdColumnName(entityClass) + " IN (" + placeholders(ownerIds.size()) + ")";

            Map<Object, Object> ownerToFk = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(fkSql)) {
                for (int i = 0; i < ownerIds.size(); i++) stmt.setObject(i + 1, ownerIds.get(i));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object ownerId = rs.getObject(1);
                        Object fkVal   = rs.getObject(2);
                        if (fkVal != null) ownerToFk.put(normalizeId(ownerId), normalizeId(fkVal));
                    }
                }
            }

            if (ownerToFk.isEmpty()) return;

            List<Object> uniqueFkIds = new ArrayList<>(new LinkedHashSet<>(ownerToFk.values()));
            Map<Object, Object> fkToTarget = new HashMap<>();

            for (List<Object> chunk : partition(uniqueFkIds)) {
                String sql = "SELECT * FROM " + targetMapper.getTableName()
                        + " WHERE " + targetMapper.getIdColumn() + " IN (" + placeholders(chunk.size()) + ")";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) stmt.setObject(i + 1, chunk.get(i));
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Object target   = targetMapper.map(rs);
                            Object targetId = getIdValue(target, targetClass);
                            fkToTarget.put(normalizeId(targetId), target);
                        }
                    }
                }
            }

            Field idField = findIdField(entityClass);
            for (T entity : entities) {
                try {
                    Object ownerId = normalizeId(idField.get(entity));
                    Object fkVal   = ownerToFk.get(ownerId);
                    if (fkVal != null) field.set(entity, fkToTarget.get(fkVal));
                } catch (IllegalAccessException e) {
                    throw new MappingException("EagerLoader: cannot inject OneToOne into " + field.getName(), e);
                }
            }
        }

        log.info("EagerLoader [OneToOne] '{}' batch-loaded for {} parents", field.getName(), entities.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> void batchLoadManyToMany(List<T> entities,
                                                Class<T> entityClass,
                                                List<Object> ownerIds,
                                                Field field,
                                                Connection conn,
                                                ConnectionManager connManager) throws SQLException {
        ManyToMany anno = field.getAnnotation(ManyToMany.class);
        Class<?> elementType = getCollectionElementType(field);
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connManager);

        String joinTable, joinCol, inverseCol;

        if (!anno.mappedBy().isEmpty()) {
            Field ownerField = Arrays.stream(elementType.getDeclaredFields())
                    .filter(f -> f.getName().equals(anno.mappedBy()))
                    .findFirst()
                    .orElseThrow(() -> new MappingException(
                            "EagerLoader: mappedBy field '" + anno.mappedBy() + "' not found in " + elementType, null));

            JoinTable jt = ownerField.getAnnotation(JoinTable.class);
            joinTable  = NamingStrategy.getJoinTableName(elementType, entityClass, jt);
            inverseCol = NamingStrategy.getJoinColumnName(jt, entityClass, false);
            joinCol    = NamingStrategy.getJoinColumnName(jt, elementType, true);
        } else {
            JoinTable jt = field.getAnnotation(JoinTable.class);
            joinTable  = NamingStrategy.getJoinTableName(entityClass, elementType, jt);
            joinCol    = NamingStrategy.getJoinColumnName(jt, entityClass, true);
            inverseCol = NamingStrategy.getJoinColumnName(jt, elementType, false);
        }

        Map<Object, List<Object>> grouped = new HashMap<>();
        String finalJoinCol = joinCol;
        String finalInverseCol = inverseCol;
        String finalJoinTable = joinTable;

        for (List<Object> chunk : partition(ownerIds)) {
            String sql = "SELECT t.*, j." + finalJoinCol + " AS __owner_id FROM "
                    + targetMapper.getTableName() + " t "
                    + "JOIN " + finalJoinTable + " j ON t." + targetMapper.getIdColumn() + " = j." + finalInverseCol
                    + " WHERE j." + finalJoinCol + " IN (" + placeholders(chunk.size()) + ")";

            log.debug("EagerLoader [ManyToMany] batch SQL: {}", sql);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) stmt.setObject(i + 1, chunk.get(i));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object child   = targetMapper.map(rs);
                        Object ownerId = rs.getObject("__owner_id");
                        grouped.computeIfAbsent(normalizeId(ownerId), k -> new ArrayList<>()).add(child);
                    }
                }
            }
        }

        Field idField = findIdField(entityClass);
        for (T entity : entities) {
            try {
                Object id = normalizeId(idField.get(entity));
                List<Object> related = grouped.getOrDefault(id, Collections.emptyList());
                injectIntoField(entity, field, related);
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot inject ManyToMany into " + field.getName(), e);
            }
        }

        log.info("EagerLoader [ManyToMany] '{}': loaded {} total child records for {} parents",
                field.getName(), grouped.values().stream().mapToInt(List::size).sum(), entities.size());
    }

    @SuppressWarnings("unchecked")
    private static void injectIntoField(Object entity, Field field, List<Object> data)
            throws IllegalAccessException {
        Object current = field.get(entity);
        if (current instanceof LazyList<?> lazyList) {
            ((LazyList<Object>) lazyList).setDelegate(data);
        } else {
            field.set(entity, data);
        }
    }

    private static <T> List<Object> collectIds(List<T> entities, Class<T> entityClass) {
        Field idField = findIdField(entityClass);
        List<Object> ids = new ArrayList<>();
        for (T entity : entities) {
            try {
                Object id = idField.get(entity);
                if (id != null) ids.add(id);
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot read ID from " + entityClass.getSimpleName(), e);
            }
        }
        return ids;
    }

    private static List<List<Object>> partition(List<Object> list) {
        List<List<Object>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += IN_CLAUSE_CHUNK_SIZE) {
            chunks.add(list.subList(i, Math.min(i + IN_CLAUSE_CHUNK_SIZE, list.size())));
        }
        return chunks;
    }

    private static String placeholders(int count) {
        return Collections.nCopies(count, "?").stream().collect(Collectors.joining(","));
    }

    private static Object normalizeId(Object id) {
        if (id instanceof Number n) return n.longValue();
        return id;
    }

    private static Field findIdField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        throw new MappingException("EagerLoader: no @Id field in " + clazz.getSimpleName(), null);
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static String getIdColumnName(Class<?> clazz) {
        Field idField = findIdField(clazz);
        return NamingStrategy.getIdColumnName(idField);
    }

    private static Object getIdValue(Object entity, Class<?> clazz) {
        try {
            return findIdField(clazz).get(entity);
        } catch (IllegalAccessException e) {
            throw new MappingException("EagerLoader: cannot read ID from " + clazz.getSimpleName(), e);
        }
    }

    private static Class<?> getCollectionElementType(Field field) {
        return (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
    }
}
