package demonstration.repostiory;

import demonstration.model.Employee;
import com.rocketbunny.swiftmapper.repository.Repository;

import java.util.Optional;

public interface EmployeeRepository extends Repository<Employee, String> {
    Optional<Employee> findByFullName(String full_name);
}
