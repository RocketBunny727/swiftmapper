package com.rocketbunny.swiftmapper.repository;

import com.rocketbunny.swiftmapper.core.ConnectionManager;

public class SwiftRepository<T, ID> extends SwiftRepositorySupport<T, ID> {

    public SwiftRepository(ConnectionManager connectionManager, Class<T> entityClass, Class<ID> idClass) {
        super(connectionManager, entityClass, idClass);
    }
}