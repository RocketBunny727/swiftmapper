package demonstration.repostiory;

import demonstration.model.Flight;
import com.rocketbunny.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface FlightRepository extends Repository<Flight, String> {
    Optional<Flight> findByFlightNumber(String number);

    List<Flight> findByDeparture_airportCode(String code);
    List<Flight> findByArrival_airportCode(String code);
    List<Flight> findByDestinationCity(String destinationCity);
    List<Flight> findByArrivalCity(String arrivalCity);
}
