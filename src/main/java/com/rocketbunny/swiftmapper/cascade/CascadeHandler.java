package com.rocketbunny.swiftmapper.cascade;

import com.rocketbunny.swiftmapper.annotations.entity.Id;
import com.rocketbunny.swiftmapper.annotations.relationship.*;
import com.rocketbunny.swiftmapper.core.Session;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class CascadeHandler {
    private final Connection connection;
    private final boolean externalTransaction;
    private final SwiftLogger log = SwiftLogger.getLogger(CascadeHandler.class);
    private final Set<Object> processingEntities = Collections.newSetFromMap(new IdentityHashMap<>());

    public CascadeHandler(Connection connection) {
        this.connection = connection;
        this.externalTransaction = isTransactionActive(connection);
    }

    private boolean isTransactionActive(Connection connection) {
        try {
            return !connection.getAutoCommit();
        } catch (SQLException e) {
            return false;
        }
    }

    public void handlePrePersist(Object entity, Set<Object> visited) throws Exception {
        processCascades(entity, CascadeType.PERSIST, visited, true);
    }

    public void handlePostPersist(Object entity, Set<Object> visited) throws Exception {
        processCascades(entity, CascadeType.PERSIST, visited, false);
    }

    public void handleMerge(Object entity, Set<Object> visited) throws Exception {
        processCascades(entity, CascadeType.MERGE, visited, null);
    }

    public void handleRemove(Object entity, Set<Object> visited) throws Exception {
        processCascades(entity, CascadeType.REMOVE, visited, null);
    }

    private void processCascades(Object entity, CascadeType action, Set<Object> visited, Boolean isPre) throws Exception {
        if (entity == null) return;

        synchronized (processingEntities) {
            if (processingEntities.contains(entity)) {
                return;
            }
            processingEntities.add(entity);
        }

        try {
            for (Field field : getAllFields(entity.getClass())) {
                field.setAccessible(true);

                if (isPre != null) {
                    boolean isManyToOne = field.isAnnotationPresent(ManyToOne.class);
                    boolean isOneToOne = field.isAnnotationPresent(OneToOne.class);
                    boolean isOneToMany = field.isAnnotationPresent(OneToMany.class);
                    boolean isManyToMany = field.isAnnotationPresent(ManyToMany.class);

                    if (isPre && !(isManyToOne || isOneToOne)) continue;
                    if (!isPre && !(isOneToMany || isManyToMany)) continue;
                }

                CascadeType[] cascades = getCascadeTypes(field);

                if (cascades != null && shouldCascade(cascades, action)) {
                    Object related = field.get(entity);
                    if (related != null) {
                        if (related instanceof Collection<?> collection) {
                            for (Object item : collection) {
                                if (item != null) {
                                    executeAction(item, action, visited);
                                }
                            }
                        } else {
                            executeAction(related, action, visited);
                        }
                    }
                }
            }
        } finally {
            synchronized (processingEntities) {
                processingEntities.remove(entity);
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

    private CascadeType[] getCascadeTypes(Field field) {
        if (field.isAnnotationPresent(ManyToOne.class)) return field.getAnnotation(ManyToOne.class).cascade();
        if (field.isAnnotationPresent(OneToOne.class)) return field.getAnnotation(OneToOne.class).cascade();
        if (field.isAnnotationPresent(OneToMany.class)) return field.getAnnotation(OneToMany.class).cascade();
        if (field.isAnnotationPresent(ManyToMany.class)) return field.getAnnotation(ManyToMany.class).cascade();
        return null;
    }

    private boolean shouldCascade(CascadeType[] types, CascadeType target) {
        for (CascadeType type : types) {
            if (type == CascadeType.ALL || type == target) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeAction(Object relatedEntity, CascadeType action, Set<Object> visited) throws Exception {
        if (visited.contains(relatedEntity)) {
            log.debug("Already visited entity {}, skipping cascade", relatedEntity.getClass().getSimpleName());
            return;
        }

        visited.add(relatedEntity);

        Class clazz = relatedEntity.getClass();
        Session session = new Session(connection, clazz);
        session.setExternalTransaction(true);

        try {
            switch (action) {
                case PERSIST -> session.saveInternal(connection, relatedEntity, visited);
                case MERGE -> session.updateInternal(connection, relatedEntity, visited);
                case REMOVE -> {
                    Object id = getEntityId(relatedEntity);
                    if (id != null) {
                        session.deleteInternal(connection, id, visited);
                    } else {
                        log.warn("Cannot cascade delete entity without ID: {}", relatedEntity.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Cascade action {} failed for entity {}", e, action, clazz.getSimpleName());
            throw new SQLException("Cascade " + action + " failed for " + clazz.getSimpleName(), e);
        }
    }

    private Object getEntityId(Object entity) throws IllegalAccessException {
        for (Field field : getAllFields(entity.getClass())) {
            if (field.isAnnotationPresent(Id.class)) {
                field.setAccessible(true);
                return field.get(entity);
            }
        }
        return null;
    }
}