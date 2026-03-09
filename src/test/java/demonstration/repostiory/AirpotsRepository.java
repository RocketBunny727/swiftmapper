package demonstration.repostiory;

import demonstration.model.Airport;
import io.github.rocketbunny727.swiftmapper.repository.Repository;

import java.util.Optional;

public interface AirpotsRepository extends Repository<Airport, String> {
    Optional<Airport> findByCode(String code);
    Optional<Airport> findByName(String name);
}
