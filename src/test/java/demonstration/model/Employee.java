package demonstration.model;

import lombok.Getter;
import lombok.Setter;
import ru.nsu.swiftmapper.annotations.entity.*;

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
    private String full_name;

    @Column(nullable = false)
    private String position;

    @Column(nullable = false)
    private LocalDate hire_date;

    @Column(nullable = false)
    private LocalDate birth_date;
}

