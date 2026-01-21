package ru.nsu.swiftmapper;

import ru.nsu.swiftmapper.core.ConnectionManager;
import ru.nsu.swiftmapper.model.User;

import java.sql.SQLException;

public class SwiftMapperTest {
    public static void main(String[] args) throws SQLException {
        ConnectionManager cm = ConnectionManager.fromConfig() // спрятать логику от пользователя
                .connect()
                .initSchema(User.class); // должны подгружаться все @Entity

        var repo = cm.repository(User.class); // спрятать логику от пользователя

        for (int i = 0; i < 10; i++) {
            User user = new User();
            user.setUsername("username" + (i + 1));
            user.setEmail("user" + (i + 1) + "@gmail.com");

            repo.save(user);
        }

        cm.close();
    }
}
