package demonstration.model;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "airports")
@Getter
@Setter
public class Airport {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "AIRPORT_", startValue = 30000001)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String city;

    @Override
    public String toString() {
        return "Airport [ID=" + id + " | " + name + " - " + code + ", LOCATION: " + city + "]";
    }
}
