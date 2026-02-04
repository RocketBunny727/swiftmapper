package ru.nsu.swiftmapper.model;

import ru.nsu.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends Repository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByAgeGreaterThan(int age);
    List<User> findByAgeLessThan(int age);
}
