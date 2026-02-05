package demonstration.model;

import lombok.Getter;
import lombok.Setter;
import ru.nsu.swiftmapper.annotations.entity.*;

import java.time.LocalDate;

@Entity
@Table(name = "passengers")
@Getter
@Setter
public class Passenger {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "PASSENGER_", startValue = 50000001)
    private String id;

    @Column(nullable = false)
    private String full_name;

    @Column(nullable = false)
    private String passport_number;

    @Column(nullable = false)
    private LocalDate birth_date;
}
