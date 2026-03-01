package com.rocketbunny.swiftmapper.repository;

import com.rocketbunny.swiftmapper.core.ConnectionManager;
import com.rocketbunny.swiftmapper.core.Session;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class SwiftRepository<T, ID> implements Repository<T, ID> {
    private final ConnectionManager connectionManager;
    private final Class<T> entityClass;

    public SwiftRepository(ConnectionManager connectionManager, Class<T> entityClass) {
        this.connectionManager = connectionManager;
        this.entityClass = entityClass;
    }

    private Session<T> createSession() throws SQLException {
        Session<T> session = new Session<>(connectionManager, entityClass);
        session.setStatementCache(connectionManager.getStatementCache());
        return session;
    }

    @Override
    public T save(T entity) {
        try {
            Session<T> session = createSession();
            return session.save(entity);
        } catch (Exception e) {
            throw new RuntimeException("Save failed", e);
        }
    }

    public List<T> saveAll(List<T> entities) {
        try {
            Session<T> session = createSession();
            return session.saveAll(entities);
        } catch (Exception e) {
            throw new RuntimeException("Batch save failed", e);
        }
    }

    @Override
    public Optional<T> findById(ID id) {
        try {
            Session<T> session = createSession();
            return session.findById(id);
        } catch (Exception e) {
            throw new RuntimeException("FindById failed", e);
        }
    }

    @Override
    public void update(T entity) {
        try {
            Session<T> session = createSession();
            session.update(entity);
        } catch (Exception e) {
            throw new RuntimeException("Update failed", e);
        }
    }

    public void updateAll(List<T> entities) {
        try {
            Session<T> session = createSession();
            session.updateAll(entities);
        } catch (Exception e) {
            throw new RuntimeException("Batch update failed", e);
        }
    }

    @Override
    public void delete(ID id) {
        try {
            Session<T> session = createSession();
            session.delete(id);
        } catch (Exception e) {
            throw new RuntimeException("Delete failed", e);
        }
    }

    public void deleteAll(List<ID> ids) {
        try {
            Session<T> session = createSession();
            session.deleteAll(ids);
        } catch (Exception e) {
            throw new RuntimeException("Batch delete failed", e);
        }
    }

    @Override
    public List<T> findAll() {
        try {
            Session<T> session = createSession();
            return session.findAll();
        } catch (Exception e) {
            throw new RuntimeException("FindAll failed", e);
        }
    }

    @Override
    public List<T> query(String sql, Object... params) {
        try {
            Session<T> session = createSession();
            return session.query(sql, params);
        } catch (Exception e) {
            throw new RuntimeException("Query failed", e);
        }
    }
}