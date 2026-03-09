package com.rocketbunny.swiftmapper.core;

import com.rocketbunny.swiftmapper.repository.Repository;
import com.rocketbunny.swiftmapper.repository.query.QueryRepositoryFactory;
import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class SwiftMapperContext {

    @Getter
    private final ConnectionManager connectionManager;
    private final Map<Class<?>, Repository<?, ?>> repositories = new HashMap<>();

    private SwiftMapperContext(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public static SwiftMapperContext fromConfig() {
        return new SwiftMapperContext(ConnectionManager.fromConfig());
    }

    public static SwiftMapperContext of(ConnectionManager connectionManager) {
        return new SwiftMapperContext(connectionManager);
    }

    public SwiftMapperContext initSchema(Class<?>... entityClasses) throws SQLException {
        connectionManager.initSchema(entityClasses);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T, ID, R extends Repository<T, ID>> R getRepository(Class<R> repositoryInterface) {
        return (R) repositories.computeIfAbsent(repositoryInterface,
                k -> QueryRepositoryFactory.createInterface(repositoryInterface,
                        detectEntityClass(repositoryInterface), connectionManager));
    }

    @SuppressWarnings("unchecked")
    public <T, ID> Repository<T, ID> getRepository(Class<T> entityClass, Class<ID> idClass) {
        return (Repository<T, ID>) repositories.computeIfAbsent(entityClass,
                k -> QueryRepositoryFactory.create(entityClass, idClass, connectionManager));
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> detectEntityClass(Class<?> repositoryInterface) {
        for (Type genericInterface : repositoryInterface.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType pt) {
                if (pt.getRawType() == Repository.class) {
                    return (Class<T>) pt.getActualTypeArguments()[0];
                }
            }
        }
        throw new IllegalArgumentException("Cannot detect entity class from " + repositoryInterface);
    }

    public void close() {
        connectionManager.close();
        repositories.clear();
    }
}