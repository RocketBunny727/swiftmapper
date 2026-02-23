package demonstration.model;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.FetchType;
import com.rocketbunny.swiftmapper.annotations.relationship.JoinColumn;
import com.rocketbunny.swiftmapper.annotations.relationship.ManyToOne;
import com.rocketbunny.swiftmapper.annotations.relationship.OneToOne;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "TICKET_", startValue = 20000001)
    private String id;

    @OneToOne
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;
}
