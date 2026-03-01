package demonstration.model;

import com.rocketbunny.swiftmapper.annotations.entity.*;
import com.rocketbunny.swiftmapper.annotations.relationship.CascadeType;
import com.rocketbunny.swiftmapper.annotations.relationship.ManyToMany;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Table(name = "services")
@Getter
@Setter
public class Service {
    @Id
    @GeneratedValue(strategy = Strategy.PATTERN, pattern = "SRV_", startValue = 80000001)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private double price;

    @ManyToMany(mappedBy = "services", cascade = CascadeType.ALL)
    private List<Ticket> tickets;

    @Override
    public String toString() {
        return "Service [ID=" + id + " | " + name + " - $" + price + "]";
    }
}