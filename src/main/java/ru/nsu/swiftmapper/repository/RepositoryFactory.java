package ru.nsu.swiftmapper.repository;

import java.sql.Connection;

public class RepositoryFactory {
    @SuppressWarnings("unchecked")
    public static <T, ID> Repository<T, ID> create(Class<T> entityClass, Connection connection) {
        return (Repository<T, ID>) new SwiftRepository<>(connection, entityClass);
    }
}
