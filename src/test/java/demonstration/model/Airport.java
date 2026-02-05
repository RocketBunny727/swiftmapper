package demonstration.model;

import lombok.Getter;
import lombok.Setter;
import ru.nsu.swiftmapper.annotations.entity.*;

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
