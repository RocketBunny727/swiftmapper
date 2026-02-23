package com.rocketbunny.swiftmapper.cascade;

import com.rocketbunny.swiftmapper.annotations.entity.Id;
import com.rocketbunny.swiftmapper.annotations.relationship.*;
import com.rocketbunny.swiftmapper.core.Session;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.Collection;

public class CascadeHandler {
    private final SwiftLogger log = SwiftLogger.getLogger(CascadeHandler.class);
    private final Connection connection;

    public CascadeHandler(Connection connection) {
        this.connection = connection;
    }

    public void handlePersist(Object entity) throws Exception {
        processCascade(entity, CascadeType.PERSIST);
    }

    public void handleMerge(Object entity) throws Exception {
        processCascade(entity, CascadeType.MERGE);
    }

    public void handleRemove(Object entity) throws Exception {
        processCascade(entity, CascadeType.REMOVE);
    }

    private void processCascade(Object entity, CascadeType targetType) throws Exception {
        Class<?> clazz = entity.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);

            CascadeType[] cascadeTypes = getCascadeTypes(field);
            if (cascadeTypes != null && shouldCascade(cascadeTypes, targetType)) {
                Object related = field.get(entity);
                if (related != null) {
                    if (related instanceof Collection<?> collection) {
                        for (Object item : collection) {
                            executeCascadeAction(item, targetType);
                        }
                    } else {
                        executeCascadeAction(related, targetType);
                    }
                }
            }
        }
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

    private void executeCascadeAction(Object entity, CascadeType action) throws Exception {
        Class<?> clazz = entity.getClass();
        Session<?> session = new Session<>(connection, clazz);

        switch (action) {
            case PERSIST -> session.save(entity);
            case MERGE -> session.update(entity);
            case REMOVE -> {
                Object id = getEntityId(entity);
                if (id != null) {
                    session.delete(id);
                }
            }
            default -> log.warn("Unknown cascade action: {}", action);
        }
    }

    private Object getEntityId(Object entity) throws IllegalAccessException {
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                field.setAccessible(true);
                return field.get(entity);
            }
        }
        return null;
    }
}