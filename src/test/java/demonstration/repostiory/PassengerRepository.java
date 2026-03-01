package demonstration.repostiory;

import demonstration.model.Passenger;
import com.rocketbunny.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface PassengerRepository extends Repository<Passenger, String> {
    Optional<Passenger> findByFull_Name(String full_name);
    List<Passenger> findByPassport_number(String passport_number);
}