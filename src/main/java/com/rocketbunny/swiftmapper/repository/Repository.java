package com.rocketbunny.swiftmapper.repository;

import com.rocketbunny.swiftmapper.criteria.CriteriaBuilder;
import com.rocketbunny.swiftmapper.criteria.SQLQueryBuilder;

import java.util.List;
import java.util.Optional;

public interface Repository<T, ID> {
    T save(T entity);
    List<T> saveAll(List<T> entities);

    T update(T entity);
    List<T> updateAll(List<T> entities);

    Optional<T> findById(ID id);
    boolean existsById(ID id);
    List<T> findAll();
    long count();

    void deleteById(ID id);
    void delete(T entity);

    default void deleteAllById(List<ID> ids) {
        if (ids != null) {
            ids.forEach(this::deleteById);
        }
    }

    default void deleteAll(List<T> entities) {
        if (entities != null) {
            entities.forEach(this::delete);
        }
    }

    default void deleteAll() {
        findAll().forEach(this::delete);
    }

    List<T> query(String sql, Object... params);

    default List<T> query(String sql, List<Object> params) {
        return query(sql, params != null ? params.toArray() : new Object[0]);
    }

    CriteriaBuilder<T> criteria();

    SQLQueryBuilder sql();
}