package demonstration.repostiory;

import demonstration.model.Passenger;
import ru.nsu.swiftmapper.repository.Repository;

import java.util.Optional;

public interface PassengerRepository extends Repository<Passenger, String> {
    Optional<Passenger> findByFull_Name(String name);
}
