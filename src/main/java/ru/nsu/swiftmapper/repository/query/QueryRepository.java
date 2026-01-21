package ru.nsu.swiftmapper.repository.query;

import ru.nsu.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface QueryRepository<T, ID> extends Repository<T, ID> {
    List<T> findByUsername(String username);
    List<T> findByEmailLike(String email);
    List<T> findByAgeGreaterThan(int age);
    Optional<T> findById(ID id);
}
