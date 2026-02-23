package demonstration.model;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.FetchType;
import com.rocketbunny.swiftmapper.annotations.relationship.JoinColumn;
import com.rocketbunny.swiftmapper.annotations.relationship.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "flights")
@Getter
@Setter
public class Flight {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "FLIGHT_", startValue = 10000001)
    private String id;

    private String flight_number;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "airplane_id", nullable = false)
    private Airplane airplane;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "flight_crew_id", nullable = false)
    private FlightCrew crew;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "departure_airport_id", nullable = false)
    private Airport departure_airport;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "arrival_airport_id", nullable = false)
    private Airport arrival_airport;

    private LocalDateTime departure_time;

    private LocalDateTime arrival_time;
}
