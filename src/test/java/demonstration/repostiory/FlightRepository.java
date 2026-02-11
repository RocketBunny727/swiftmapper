package demonstration.repostiory;

import demonstration.model.Flight;
import ru.nsu.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface FlightRepository extends Repository<Flight, String> {
    Optional<Flight> findByFlightNumber(String number);

    List<Flight> findByDestinationAirportCode(String destinationAirportCode);
    List<Flight> findByDestinationCity(String destinationCity);
    List<Flight> findByArrivalAirportCode(String arrivalAirportCode);
    List<Flight> findByArrivalCity(String arrivalCity);
}
