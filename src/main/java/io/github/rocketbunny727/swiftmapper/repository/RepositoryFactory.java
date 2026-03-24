package io.github.rocketbunny727.swiftmapper.repository;

import io.github.rocketbunny727.swiftmapper.core.ConnectionManager;

public class RepositoryFactory {
    public static <T, ID> SwiftRepositoryPattern<T, ID> create(Class<T> entityClass, Class<ID> idClass,
                                                               ConnectionManager connectionManager) {
        return new SwiftRepository<>(connectionManager, entityClass, idClass);
    }
}