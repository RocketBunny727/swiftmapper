package demonstration.model;

import io.github.rocketbunny727.swiftmapper.annotations.entity.*;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.FetchType;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.JoinColumn;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "flight_crews")
@Getter
@Setter
public class FlightCrew {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "CREW_", startValue = 50000001)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "capitan_id", nullable = false)
    private Employee capitan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "co_pilot_id", nullable = false)
    private Employee coPilot;
}
