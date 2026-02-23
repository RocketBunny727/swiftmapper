package com.rocketbunny.swiftmapper.repository;

import com.rocketbunny.swiftmapper.core.ConnectionManager;

public class RepositoryFactory {
    public static <T, ID> Repository<T, ID> create(Class<T> entityClass, ConnectionManager connectionManager) {
        return new SwiftRepository<>(connectionManager, entityClass);
    }
}