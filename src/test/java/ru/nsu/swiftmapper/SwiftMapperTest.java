package ru.nsu.swiftmapper;

import ru.nsu.swiftmapper.core.ConnectionManager;
import ru.nsu.swiftmapper.model.Pet;
import ru.nsu.swiftmapper.model.User;

import java.sql.SQLException;

public class SwiftMapperTest {
    public static void main(String[] args) throws SQLException {
        ConnectionManager cm = ConnectionManager.fromConfig() // спрятать логику от пользователя
                .connect()
                .initSchema(User.class)
                .initSchema(Pet.class);// должны подгружаться все @Entity

        var repoU = cm.repository(User.class); // спрятать логику от пользователя
        var repoP = cm.repository(Pet.class);

        for (int i = 0; i < 10; i++) {
            User user = new User();
            user.setUsername("username" + (i + 1));
            user.setEmail("user" + (i + 1) + "@gmail.com");

            repoU.save(user);
        }

        for (int i = 0; i < 10; i++) {
            Pet pet = new Pet();
            pet.setName("pet_" + (i + 1));

            repoP.save(pet);
        }

        cm.close();
    }
}
