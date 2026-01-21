package ru.nsu.swiftmapper.model;

import lombok.Data;
import ru.nsu.swiftmapper.annotations.*;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "USER_", startValue = 1001)
    @Column(name = "id")
    private String id;

    @Column(name = "username")
    private String username;

    @Column(name = "email")
    private String email;
}
