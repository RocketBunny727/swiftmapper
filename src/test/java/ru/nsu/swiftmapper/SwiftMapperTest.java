package ru.nsu.swiftmapper;

import ru.nsu.swiftmapper.core.ConnectionManager;
import ru.nsu.swiftmapper.model.User;
import ru.nsu.swiftmapper.model.UserRepository;
import ru.nsu.swiftmapper.repository.query.QueryRepositoryFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class SwiftMapperTest {
    public static void main(String[] args) throws SQLException {
        ConnectionManager cm = ConnectionManager.fromConfig()
                .initSchema(User.class)
                .connect();
        UserRepository repo = QueryRepositoryFactory.create(
                UserRepository.class,
                User.class,
                cm.connection()
        );

        List<User> adults = repo.findByAgeGreaterThan(18);
        List<User> children = repo.findByAgeLessThan(18);

        System.out.println("adults: " + adults);
        System.out.println("children: " + children);

        Optional<User> curr = repo.findByEmail("username4@gmail.com");
        if (curr.isPresent()) {
            System.out.println(curr.get().toString());
        } else {
            System.out.println("No user found");
        }
    }
}
