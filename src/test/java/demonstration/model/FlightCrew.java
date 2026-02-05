package demonstration.model;

import ru.nsu.swiftmapper.annotations.entity.*;
import ru.nsu.swiftmapper.annotations.relationship.*;

@Entity
@Table(name = "flight_crews")
public class FlightCrew {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "CREW_", startValue = 50000001)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "capitan_id", nullable = false)
    private Employee capitan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "co_pilot_id", nullable = false)
    private Employee co_pilot;
}
