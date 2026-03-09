package demonstration.model;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.FetchType;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.JoinColumn;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.ManyToOne;
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

    private String flightNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "airplane_id", nullable = false)
    private Airplane airplane;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "flight_crew_id", nullable = false)
    private FlightCrew crew;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "departure_airport_id", nullable = false)
    private Airport departureAirport;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "arrival_airport_id", nullable = false)
    private Airport arrivalAirport;

    private LocalDateTime departureTime;

    private LocalDateTime arrivalTime;
}
