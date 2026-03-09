package demonstration.repostiory;

import demonstration.model.Airplane;
import io.github.rocketbunny727.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface AirplaneRepository extends Repository<Airplane, String> {
    List<Airplane> findByManufacturer(String manufacturer);
    Optional<Airplane> findByNumber(String number);
    List<Airplane> findByModelContaining(String pattern);
}