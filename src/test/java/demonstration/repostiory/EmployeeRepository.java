package demonstration.repostiory;

import demonstration.model.Employee;
import io.github.rocketbunny727.swiftmapper.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends Repository<Employee, String> {
    Optional<Employee> findByFullName(String full_name);
    List<Employee> findByPosition(String position);
}