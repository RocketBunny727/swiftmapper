package demonstration.repostiory;

import io.github.rocketbunny727.swiftmapper.repository.Repository;
import demonstration.model.Service;
import java.util.Optional;

public interface ServiceRepository extends Repository<Service, String> {
    Optional<Service> findByName(String name);
}