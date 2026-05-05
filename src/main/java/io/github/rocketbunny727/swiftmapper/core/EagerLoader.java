package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Id;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.FetchType;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.JoinTable;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.ManyToMany;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.ManyToOne;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.OneToMany;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.OneToOne;
import io.github.rocketbunny727.swiftmapper.dialect.SqlDialect;
import io.github.rocketbunny727.swiftmapper.dialect.SqlRenderer;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class EagerLoader {

    private static final SwiftLogger log = SwiftLogger.getLogger(EagerLoader.class);
    private static final String OWNER_ALIAS = "swift_owner_id";

    private EagerLoader() {
    }

    public static <T> void batchLoad(List<T> entities,
                                     Class<T> entityClass,
                                     Connection conn,
                                     ConnectionManager connManager,
                                     String... relations) throws SQLException {
        if (entities == null || entities.isEmpty() || relations == null || relations.length == 0) {
            return;
        }

        batchLoadInternal(entities, entityClass, conn, connManager, relations, new LinkedHashSet<>());
    }

    private static void batchLoadInternal(List<?> entities,
                                          Class<?> entityClass,
                                          Connection conn,
                                          ConnectionManager connManager,
                                          String[] relations,
                                          Set<String> activePaths) throws SQLException {
        if (entities == null || entities.isEmpty() || relations == null || relations.length == 0) {
            return;
        }

        List<Object> ownerIds = collectIds(entities, entityClass);
        if (ownerIds.isEmpty()) {
            return;
        }

        for (String relation : relations) {
            Field field = findField(entityClass, relation);
            if (field == null) {
                log.warn("EagerLoader: relation '{}' not found in {}", relation, entityClass.getSimpleName());
                continue;
            }

            String relationKey = entityClass.getName() + "#" + field.getName();
            if (!activePaths.add(relationKey)) {
                log.debug("EagerLoader: cycle detected for {}, skipping nested eager load", relationKey);
                continue;
            }

            try {
                List<Object> loadedTargets = loadOneRelation(entities, entityClass, ownerIds, field, conn, connManager);
                cascadeEagerRelations(loadedTargets, field, conn, connManager, activePaths);
            } finally {
                activePaths.remove(relationKey);
            }
        }
    }

    private static List<Object> loadOneRelation(List<?> entities,
                                                Class<?> entityClass,
                                                List<Object> ownerIds,
                                                Field field,
                                                Connection conn,
                                                ConnectionManager connManager) throws SQLException {
        field.setAccessible(true);

        if (field.isAnnotationPresent(OneToMany.class)) {
            return batchLoadOneToMany(entities, entityClass, ownerIds, field, conn, connManager);
        }
        if (field.isAnnotationPresent(ManyToOne.class)) {
            return batchLoadManyToOne(entities, entityClass, ownerIds, field, conn, connManager);
        }
        if (field.isAnnotationPresent(OneToOne.class)) {
            return batchLoadOneToOne(entities, entityClass, ownerIds, field, conn, connManager);
        }
        if (field.isAnnotationPresent(ManyToMany.class)) {
            return batchLoadManyToMany(entities, entityClass, ownerIds, field, conn, connManager);
        }

        log.warn("EagerLoader: field '{}' is not a relationship field, skipping", field.getName());
        return Collections.emptyList();
    }

    private static List<Object> batchLoadOneToMany(List<?> entities,
                                                   Class<?> entityClass,
                                                   List<Object> ownerIds,
                                                   Field field,
                                                   Connection conn,
                                                   ConnectionManager connManager) throws SQLException {
        OneToMany anno = field.getAnnotation(OneToMany.class);
        Class<?> elementType = getCollectionElementType(field);
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connManager);
        String fkColumn = NamingStrategy.getOneToManyFkColumn(entityClass, anno.mappedBy());
        SqlRenderer sql = renderer(connManager);

        Map<Object, List<Object>> grouped = new HashMap<>();
        List<Object> loadedChildren = new ArrayList<>();

        for (List<Object> chunk : partition(ownerIds, sql)) {
            String query = sql.selectAllWhereIn(targetMapper.getTableName(), fkColumn, chunk.size());

            log.debug("EagerLoader [OneToMany] batch SQL: {}", query);

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                for (int i = 0; i < chunk.size(); i++) {
                    stmt.setObject(i + 1, chunk.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object child = targetMapper.map(rs);
                        Object fkVal = rs.getObject(fkColumn);
                        loadedChildren.add(child);
                        grouped.computeIfAbsent(normalizeId(fkVal), key -> new ArrayList<>()).add(child);
                    }
                }
            }
        }

        Field idField = findIdField(entityClass);
        for (Object entity : entities) {
            try {
                Object id = idField.get(entity);
                List<Object> related = grouped.getOrDefault(normalizeId(id), Collections.emptyList());
                injectCollection(entity, field, related);
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot inject OneToMany into " + field.getName(), e);
            }
        }

        log.info("EagerLoader [OneToMany] '{}': loaded {} child records for {} parents",
                field.getName(), loadedChildren.size(), entities.size());

        return deduplicateEntities(loadedChildren);
    }

    private static List<Object> batchLoadManyToOne(List<?> entities,
                                                   Class<?> entityClass,
                                                   List<Object> ownerIds,
                                                   Field field,
                                                   Connection conn,
                                                   ConnectionManager connManager) throws SQLException {
        Class<?> targetClass = field.getType();
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connManager);
        String fkColumn = NamingStrategy.getForeignKeyColumn(field);
        String tableName = NamingStrategy.getTableName(entityClass);
        String idColumn = getIdColumnName(entityClass);
        SqlRenderer sql = renderer(connManager);

        Map<Object, Object> ownerToFk = new LinkedHashMap<>();

        for (List<Object> chunk : partition(ownerIds, sql)) {
            String fkSql = sql.selectColumnsWhereIn(tableName, List.of(idColumn, fkColumn), idColumn, chunk.size());

            log.debug("EagerLoader [ManyToOne] FK-fetch SQL: {}", fkSql);
            try (PreparedStatement stmt = conn.prepareStatement(fkSql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    stmt.setObject(i + 1, chunk.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object ownerId = rs.getObject(1);
                        Object fkVal = rs.getObject(2);
                        ownerToFk.put(normalizeId(ownerId), fkVal != null ? normalizeId(fkVal) : null);
                    }
                }
            }
        }

        Map<Object, Object> fkToTarget = loadTargetsById(targetMapper, targetClass, ownerToFk.values(), conn, sql);
        Field idField = findIdField(entityClass);
        List<Object> loadedTargets = new ArrayList<>();

        for (Object entity : entities) {
            try {
                Object ownerId = normalizeId(idField.get(entity));
                Object fkVal = ownerToFk.get(ownerId);
                Object target = fkVal != null ? fkToTarget.get(fkVal) : null;
                injectSingleValue(entity, field, target);
                if (target != null) {
                    loadedTargets.add(target);
                }
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot inject ManyToOne into " + field.getName(), e);
            }
        }

        log.info("EagerLoader [ManyToOne] '{}': resolved {} unique FK targets for {} parents",
                field.getName(), fkToTarget.size(), entities.size());

        return deduplicateEntities(loadedTargets);
    }

    private static List<Object> batchLoadOneToOne(List<?> entities,
                                                  Class<?> entityClass,
                                                  List<Object> ownerIds,
                                                  Field field,
                                                  Connection conn,
                                                  ConnectionManager connManager) throws SQLException {
        OneToOne anno = field.getAnnotation(OneToOne.class);
        Class<?> targetClass = field.getType();
        EntityMapper<?> targetMapper = EntityMapper.getInstance(targetClass, connManager);
        Field idField = findIdField(entityClass);
        List<Object> loadedTargets = new ArrayList<>();
        SqlRenderer sql = renderer(connManager);

        if (!anno.mappedBy().isEmpty()) {
            String fkColumn = NamingStrategy.getOneToManyFkColumn(entityClass, anno.mappedBy());
            Map<Object, Object> ownerToTarget = new HashMap<>();

            for (List<Object> chunk : partition(ownerIds, sql)) {
                String query = sql.selectAllWhereIn(targetMapper.getTableName(), fkColumn, chunk.size());

                log.debug("EagerLoader [OneToOne mappedBy] batch SQL: {}", query);
                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        stmt.setObject(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Object target = targetMapper.map(rs);
                            Object fkVal = rs.getObject(fkColumn);
                            ownerToTarget.put(normalizeId(fkVal), target);
                            loadedTargets.add(target);
                        }
                    }
                }
            }

            for (Object entity : entities) {
                try {
                    Object ownerId = normalizeId(idField.get(entity));
                    injectSingleValue(entity, field, ownerToTarget.get(ownerId));
                } catch (IllegalAccessException e) {
                    throw new MappingException("EagerLoader: cannot inject OneToOne(mappedBy) into " + field.getName(), e);
                }
            }
        } else {
            String fkColumn = NamingStrategy.getOneToOneFkColumn(field);
            String tableName = NamingStrategy.getTableName(entityClass);
            String idColumn = getIdColumnName(entityClass);
            Map<Object, Object> ownerToFk = new HashMap<>();

            for (List<Object> chunk : partition(ownerIds, sql)) {
                String fkSql = sql.selectColumnsWhereIn(tableName, List.of(idColumn, fkColumn), idColumn, chunk.size());

                log.debug("EagerLoader [OneToOne] FK-fetch SQL: {}", fkSql);
                try (PreparedStatement stmt = conn.prepareStatement(fkSql)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        stmt.setObject(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Object ownerId = rs.getObject(1);
                            Object fkVal = rs.getObject(2);
                            ownerToFk.put(normalizeId(ownerId), fkVal != null ? normalizeId(fkVal) : null);
                        }
                    }
                }
            }

            Map<Object, Object> fkToTarget = loadTargetsById(targetMapper, targetClass, ownerToFk.values(), conn, sql);

            for (Object entity : entities) {
                try {
                    Object ownerId = normalizeId(idField.get(entity));
                    Object fkVal = ownerToFk.get(ownerId);
                    Object target = fkVal != null ? fkToTarget.get(fkVal) : null;
                    injectSingleValue(entity, field, target);
                    if (target != null) {
                        loadedTargets.add(target);
                    }
                } catch (IllegalAccessException e) {
                    throw new MappingException("EagerLoader: cannot inject OneToOne into " + field.getName(), e);
                }
            }
        }

        log.info("EagerLoader [OneToOne] '{}': batch-loaded for {} parents", field.getName(), entities.size());

        return deduplicateEntities(loadedTargets);
    }

    private static List<Object> batchLoadManyToMany(List<?> entities,
                                                    Class<?> entityClass,
                                                    List<Object> ownerIds,
                                                    Field field,
                                                    Connection conn,
                                                    ConnectionManager connManager) throws SQLException {
        ManyToMany anno = field.getAnnotation(ManyToMany.class);
        Class<?> elementType = getCollectionElementType(field);
        EntityMapper<?> targetMapper = EntityMapper.getInstance(elementType, connManager);
        SqlRenderer sql = renderer(connManager);

        Field ownerField = null;
        if (!anno.mappedBy().isEmpty()) {
            ownerField = findField(elementType, anno.mappedBy());
            if (ownerField == null) {
                throw new MappingException(
                        "EagerLoader: mappedBy field '" + anno.mappedBy() + "' not found in " + elementType.getSimpleName(),
                        null
                );
            }
        }
        JoinTable joinTableAnnotation = ownerField != null ? ownerField.getAnnotation(JoinTable.class) : field.getAnnotation(JoinTable.class);

        String joinTable = ownerField != null
                ? NamingStrategy.getJoinTableName(elementType, entityClass, joinTableAnnotation)
                : NamingStrategy.getJoinTableName(entityClass, elementType, joinTableAnnotation);
        String joinCol = ownerField != null
                ? NamingStrategy.getJoinColumnName(joinTableAnnotation, entityClass, false)
                : NamingStrategy.getJoinColumnName(joinTableAnnotation, entityClass, true);
        String inverseCol = ownerField != null
                ? NamingStrategy.getJoinColumnName(joinTableAnnotation, elementType, true)
                : NamingStrategy.getJoinColumnName(joinTableAnnotation, elementType, false);

        Map<Object, List<Object>> grouped = new HashMap<>();
        List<Object> loadedChildren = new ArrayList<>();

        for (List<Object> chunk : partition(ownerIds, sql)) {
            String query = sql.selectManyToMany(targetMapper.getTableName(), joinTable,
                    targetMapper.getIdColumn(), joinCol, inverseCol, OWNER_ALIAS, chunk.size());

            log.debug("EagerLoader [ManyToMany] batch SQL: {}", query);
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                for (int i = 0; i < chunk.size(); i++) {
                    stmt.setObject(i + 1, chunk.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object child = targetMapper.map(rs);
                        Object ownerId = rs.getObject(OWNER_ALIAS);
                        loadedChildren.add(child);
                        grouped.computeIfAbsent(normalizeId(ownerId), key -> new ArrayList<>()).add(child);
                    }
                }
            }
        }

        Field idField = findIdField(entityClass);
        for (Object entity : entities) {
            try {
                Object id = normalizeId(idField.get(entity));
                List<Object> related = grouped.getOrDefault(id, Collections.emptyList());
                injectCollection(entity, field, related);
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot inject ManyToMany into " + field.getName(), e);
            }
        }

        log.info("EagerLoader [ManyToMany] '{}': loaded {} total child records for {} parents",
                field.getName(), loadedChildren.size(), entities.size());

        return deduplicateEntities(loadedChildren);
    }

    private static Map<Object, Object> loadTargetsById(EntityMapper<?> targetMapper,
                                                       Class<?> targetClass,
                                                       Collection<Object> targetIds,
                                                       Connection conn,
                                                       SqlRenderer sql) throws SQLException {
        List<Object> uniqueIds = targetIds.stream()
                .filter(id -> id != null)
                .map(EagerLoader::normalizeId)
                .distinct()
                .toList();

        Map<Object, Object> fkToTarget = new HashMap<>();
        for (List<Object> chunk : partition(uniqueIds, sql)) {
            String query = sql.selectAllWhereIn(targetMapper.getTableName(), targetMapper.getIdColumn(), chunk.size());

            log.debug("EagerLoader target batch SQL: {}", query);
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                for (int i = 0; i < chunk.size(); i++) {
                    stmt.setObject(i + 1, chunk.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object target = targetMapper.map(rs);
                        Object targetId = getIdValue(target, targetClass);
                        fkToTarget.put(normalizeId(targetId), target);
                    }
                }
            }
        }

        return fkToTarget;
    }

    private static void cascadeEagerRelations(List<Object> relatedEntities,
                                              Field relationField,
                                              Connection conn,
                                              ConnectionManager connManager,
                                              Set<String> activePaths) throws SQLException {
        if (relatedEntities == null || relatedEntities.isEmpty()) {
            return;
        }

        Class<?> targetClass = relationField.getType();
        if (Collection.class.isAssignableFrom(targetClass)) {
            targetClass = getCollectionElementType(relationField);
        }

        String[] eagerRelations = getEagerRelationNames(targetClass);
        if (eagerRelations.length == 0) {
            return;
        }

        batchLoadInternal(deduplicateEntities(relatedEntities), targetClass, conn, connManager, eagerRelations, activePaths);
    }

    @SuppressWarnings("unchecked")
    private static void injectCollection(Object entity, Field field, List<Object> data) throws IllegalAccessException {
        Object current = field.get(entity);
        if (current instanceof LazyList<?> lazyList) {
            ((LazyList<Object>) lazyList).setDelegate(data);
        } else {
            field.set(entity, data);
        }
    }

    private static void injectSingleValue(Object entity, Field field, Object data) throws IllegalAccessException {
        field.set(entity, data);
    }

    private static List<Object> collectIds(List<?> entities, Class<?> entityClass) {
        Field idField = findIdField(entityClass);
        List<Object> ids = new ArrayList<>();

        for (Object entity : entities) {
            try {
                Object id = idField.get(entity);
                if (id != null) {
                    ids.add(id);
                }
            } catch (IllegalAccessException e) {
                throw new MappingException("EagerLoader: cannot read ID from " + entityClass.getSimpleName(), e);
            }
        }

        return ids;
    }

    private static List<Object> deduplicateEntities(Collection<?> entities) {
        Map<String, Object> unique = new LinkedHashMap<>();
        for (Object entity : entities) {
            if (entity == null) {
                continue;
            }

            String key;
            try {
                Object id = getIdValue(entity, entity.getClass());
                key = entity.getClass().getName() + ":" + normalizeId(id);
            } catch (Exception e) {
                key = entity.getClass().getName() + "@" + System.identityHashCode(entity);
            }
            unique.putIfAbsent(key, entity);
        }
        return new ArrayList<>(unique.values());
    }

    private static List<List<Object>> partition(List<Object> list, SqlRenderer sql) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Object>> chunks = new ArrayList<>();
        int chunkSize = sql.inClauseLimit();
        for (int i = 0; i < list.size(); i += chunkSize) {
            chunks.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return chunks;
    }

    private static String placeholders(int count) {
        return Collections.nCopies(count, "?").stream().collect(Collectors.joining(","));
    }

    private static SqlRenderer renderer(ConnectionManager connManager) {
        return connManager != null ? connManager.getSqlRenderer() : new SqlRenderer(SqlDialect.GENERIC);
    }

    private static Object normalizeId(Object id) {
        if (id instanceof Number number) {
            return number.longValue();
        }
        return id;
    }

    private static Field findIdField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    return field;
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
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String getIdColumnName(Class<?> clazz) {
        return NamingStrategy.getIdColumnName(findIdField(clazz));
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

    public static String[] getEagerRelationNames(Class<?> entityClass) {
        List<String> names = new ArrayList<>();
        for (Field field : getAllFields(entityClass)) {
            if (field.isAnnotationPresent(OneToMany.class)
                    && field.getAnnotation(OneToMany.class).fetch() == FetchType.EAGER) {
                names.add(field.getName());
            } else if (field.isAnnotationPresent(ManyToOne.class)
                    && field.getAnnotation(ManyToOne.class).fetch() != FetchType.LAZY) {
                names.add(field.getName());
            } else if (field.isAnnotationPresent(OneToOne.class)
                    && field.getAnnotation(OneToOne.class).fetch() != FetchType.LAZY) {
                names.add(field.getName());
            } else if (field.isAnnotationPresent(ManyToMany.class)
                    && field.getAnnotation(ManyToMany.class).fetch() == FetchType.EAGER) {
                names.add(field.getName());
            }
        }
        return names.toArray(new String[0]);
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }
}
