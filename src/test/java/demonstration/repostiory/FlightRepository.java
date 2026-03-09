package demonstration.repostiory;

import demonstration.model.Flight;
import com.rocketbunny.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface FlightRepository extends Repository<Flight, String> {
    Optional<Flight> findByFlightNumber(String flight_number);
    List<Flight> findByDepartureAirportCode(String code);
    List<Flight> findByArrivalAirportCode(String code);
    List<Flight> findByAirplaneManufacturer(String manufacturer);
}