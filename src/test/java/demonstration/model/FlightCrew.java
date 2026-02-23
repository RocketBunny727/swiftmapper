package demonstration.model;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.FetchType;
import com.rocketbunny.swiftmapper.annotations.relationship.JoinColumn;
import com.rocketbunny.swiftmapper.annotations.relationship.ManyToOne;
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
    private Employee co_pilot;
}
