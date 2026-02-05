package demonstration.repostiory;

import demonstration.model.Airplane;
import demonstration.model.Flight;
import ru.nsu.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface AirplaneRepository extends Repository<Airplane, String> {
    List<Airplane> findByManufacturer(Flight flight);
    Optional<Airplane> findByNumber(String code);
}
