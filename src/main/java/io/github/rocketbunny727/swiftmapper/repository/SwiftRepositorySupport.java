package io.github.rocketbunny727.swiftmapper.repository;

import io.github.rocketbunny727.swiftmapper.core.ConnectionManager;
import io.github.rocketbunny727.swiftmapper.core.Session;
import io.github.rocketbunny727.swiftmapper.criteria.CriteriaBuilder;
import io.github.rocketbunny727.swiftmapper.criteria.SQLQueryBuilder;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Id;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class SwiftRepositorySupport<T, ID> implements Repository<T, ID> {

    protected final ConnectionManager connectionManager;
    protected final Class<T> entityClass;
    protected final Class<ID> idClass;

    public SwiftRepositorySupport(ConnectionManager connectionManager, Class<T> entityClass, Class<ID> idClass) {
        this.connectionManager = connectionManager;
        this.entityClass = entityClass;
        this.idClass = idClass;
    }

    protected Session<T> createSession() throws SQLException {
        Session<T> session = new Session<>(connectionManager, entityClass);
        session.setStatementCache(connectionManager.getStatementCache());
        return session;
    }

    @Override
    public T save(T entity) {
        try {
            return createSession().save(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save entity", e);
        }
    }

    @Override
    public List<T> saveAll(List<T> entities) {
        try {
            return createSession().saveAll(entities);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save entities", e);
        }
    }

    @Override
    public T update(T entity) {
        try {
            Session<T> session = createSession();
            session.update(entity);
            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update entity", e);
        }
    }

    @Override
    public List<T> updateAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        List<T> results = new ArrayList<>();
        try {
            Session<T> session = createSession();
            session.updateAll(entities);
            results.addAll(entities);
        } catch (SQLException e) {
            for (T entity : entities) {
                results.add(update(entity));
            }
        }
        return results;
    }

    @Override
    public Optional<T> findById(ID id) {
        try {
            return createSession().findById(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entity by id", e);
        }
    }

    @Override
    public boolean existsById(ID id) {
        return findById(id).isPresent();
    }

    @Override
    public List<T> findAll() {
        try {
            return createSession().findAll();
        } catch (Exception e) {
            throw new RuntimeException("Failed to find all entities", e);
        }
    }

    @Override
    public long count() {
        try {
            Session<T> session = createSession();
            SQLQueryBuilder builder = new SQLQueryBuilder();
            var query = builder.from(entityClass).buildCount();
            List<?> result = session.query(query.getSql(), query.getParams().toArray(new Object[0]));
            return result.isEmpty() ? 0 : ((Number) result.get(0)).longValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to count entities", e);
        }
    }

    @Override
    public void deleteById(ID id) {
        try {
            createSession().delete(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete entity", e);
        }
    }

    @Override
    public void delete(T entity) {
        try {
            ID id = extractId(entity);
            deleteById(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete entity", e);
        }
    }

    @Override
    public List<T> query(String sql, Object... params) {
        try {
            return createSession().query(sql, params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute query", e);
        }
    }

    @Override
    public CriteriaBuilder<T> criteria() {
        return new CriteriaBuilder<>(entityClass);
    }

    @Override
    public SQLQueryBuilder sql() {
        return new SQLQueryBuilder().from(entityClass);
    }

    @SuppressWarnings("unchecked")
    protected ID extractId(T entity) {
        try {
            for (var field : entityClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    return (ID) field.get(entity);
                }
            }
            throw new IllegalStateException("No @Id field found in " + entityClass.getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to extract id", e);
        }
    }

    protected String getTableName() {
        return NamingStrategy.getTableName(entityClass);
    }

    protected ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    protected Class<ID> getIdClass() {
        return idClass;
    }
}