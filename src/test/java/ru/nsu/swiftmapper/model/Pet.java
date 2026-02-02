package ru.nsu.swiftmapper.model;

import lombok.Data;
import ru.nsu.swiftmapper.annotations.*;

@Entity
@Table(name = "pets")
@Data
public class Pet {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "PET_", startValue = 20001)
    private String id;

    private String name;
}
