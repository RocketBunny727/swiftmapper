package demonstration.repostiory;

import demonstration.model.FlightCrew;
import ru.nsu.swiftmapper.repository.Repository;

import java.util.Optional;

public interface FlightCrewRepository extends Repository<FlightCrew, String> {
    Optional<FlightCrew> findByCapitanFullName(String capitanFullName);
    Optional<FlightCrew> findByCoPilotFullName(String copilotFullName);
}
