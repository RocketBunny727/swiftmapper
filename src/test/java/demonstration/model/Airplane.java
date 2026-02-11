package demonstration.model;

import lombok.Getter;
import lombok.Setter;
import ru.nsu.swiftmapper.annotations.entity.*;

@Entity
@Table(name = "airplanes")
@Getter
@Setter
public class Airplane {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "AIRCRAFT_", startValue = 40000001)
    private String id;

    @Column(nullable = false)
    private String manufacturer;

    private String model;

    @Column(nullable = false)
    private int PassCapacity;

    @Column(nullable = false)
    private String number;

    @Override
    public String toString() {
        return "Airplane [ID:" + id + " | " + manufacturer + " " + model + " | " + PassCapacity + ". NUMBER: " + number + "]";
    }
}
