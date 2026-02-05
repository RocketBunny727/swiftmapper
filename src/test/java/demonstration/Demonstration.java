package demonstration;

import demonstration.model.*;
import demonstration.repostiory.*;
import ru.nsu.swiftmapper.core.ConnectionManager;
import ru.nsu.swiftmapper.repository.query.QueryRepositoryFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class Demonstration {
    public static void main(String[] args) throws SQLException {

        // Connect and init schema
        // Подключение и инициализация схемы данных
        ConnectionManager cm = ConnectionManager.fromConfig()
                .initSchema(
                        Employee.class,
                        FlightCrew.class,
                        Passenger.class,
                        Airport.class,
                        Airplane.class,
                        Flight.class,
                        Ticket.class)
                .connect();

        Connection connection = cm.connection();


        // Create repositories - context of your database
        // Создание репозиториев - контекста вашей базы данных
        var airports = QueryRepositoryFactory.create(AirpotsRepository.class, Airport.class, connection);
        var airplanes = QueryRepositoryFactory.create(AirplaneRepository.class, Airplane.class, connection);
        var passengers = QueryRepositoryFactory.create(PassengerRepository.class, Passenger.class, connection);
        var employees = QueryRepositoryFactory.create(EmployeeRepository.class, Employee.class, connection);
        var flight_crews = QueryRepositoryFactory.create(FlightCrewRepository.class, FlightCrew.class, connection);
        var tickets = QueryRepositoryFactory.create(TicketRepository.class, Ticket.class, connection);
        var flights = QueryRepositoryFactory.create(FlightRepository.class, Flight.class, connection);

        // Next do something with your entities - CRUD ;)
        // Далее взаимодействуйте с вашими сущностями - CRUD операции ;)
    }
}
