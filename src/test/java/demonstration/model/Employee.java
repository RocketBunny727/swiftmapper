package demonstration.model;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "employees")
@Getter
@Setter
public class Employee {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "EMPL_", startValue = 40000001)
    private String id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String position;

    @Column(nullable = false)
    private LocalDate hireDate;

    @Column(nullable = false)
    private LocalDate birthDate;

    @Override
    public String toString() {
        return "Employee [ID = " + id + ", full name=" + fullName + ", position=" + position +
                ", hireDate=" + hireDate + ", birthDate=" + birthDate + "]";
    }
}

