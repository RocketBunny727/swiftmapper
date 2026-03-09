package demonstration.repostiory;

import demonstration.model.FlightCrew;
import io.github.rocketbunny727.swiftmapper.repository.Repository;

import java.util.Optional;

public interface FlightCrewRepository extends Repository<FlightCrew, String> {
    Optional<FlightCrew> findByCapitanFullName(String fullName);
    Optional<FlightCrew> findByCoPilotFullName(String copilotFullName);
}
