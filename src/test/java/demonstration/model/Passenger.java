package demonstration.model;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "passengers")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Passenger {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "PASSENGER_", startValue = 50000001)
    private String id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String passportNumber;

    @Column(nullable = false)
    private LocalDate birthDate;
}
