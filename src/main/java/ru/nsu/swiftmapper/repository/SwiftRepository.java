package ru.nsu.swiftmapper.repository;

import ru.nsu.swiftmapper.core.Session;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

public class SwiftRepository<T, ID> implements Repository<T, ID> {
    private final Session<T> session;

    public SwiftRepository(Connection connection, Class<T> entityClass) {
        this.session = new Session<>(connection, entityClass);
    }

    @Override
    public T save(T entity) {
        try {
            return session.save(entity);
        } catch (Exception e) {
            throw new RuntimeException("Save failed", e);
        }
    }

    @Override
    public Optional<T> findById(ID id) {
        try {
            return session.findById(id);
        } catch (Exception e) {
            throw new RuntimeException("FindById failed", e);
        }
    }

    @Override
    public void update(T entity) {
        try {
            session.update(entity);
        } catch (Exception e) {
            throw new RuntimeException("Update failed", e);
        }
    }

    @Override
    public void delete(ID id) {
        try {
            session.delete(id);
        } catch (Exception e) {
            throw new RuntimeException("Delete failed", e);
        }
    }

    @Override
    public List<T> findAll() {
        try {
            return session.findAll();
        } catch (Exception e) {
            throw new RuntimeException("FindAll failed", e);
        }
    }

    @Override
    public List<T> query(String sql, Object... params) {
        try {
            return session.query(sql, params);
        } catch (Exception e) {
            throw new RuntimeException("Query failed", e);
        }
    }
}