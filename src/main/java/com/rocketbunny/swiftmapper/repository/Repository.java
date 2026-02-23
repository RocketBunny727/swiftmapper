package com.rocketbunny.swiftmapper.repository;

import java.util.List;
import java.util.Optional;

public interface Repository<T, ID> {
    T save(T entity);
    Optional<T> findById(ID id);
    void update(T entity);
    void delete(ID id);
    List<T> findAll();
    List<T> query(String sql, Object... params);
}
