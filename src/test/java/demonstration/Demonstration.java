package demonstration;

import demonstration.model.*;
import demonstration.repostiory.*;
import ru.nsu.swiftmapper.core.ConnectionManager;
import ru.nsu.swiftmapper.repository.query.QueryRepositoryFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class Demonstration {
    public static void main(String[] args) throws SQLException {

        // Connect and init schema
        // Подключение и инициализация схемы данных
        ConnectionManager cm = ConnectionManager.fromConfig()
                .initSchema( // Очень важен порядок инициализации (сначала внутренние сущности, а затем внешние)
                        Airplane.class,
                        Airport.class,
                        Employee.class,
                        Passenger.class,
                        FlightCrew.class, // FlightCrew включает Employee поэтому сначала Employee потом FlightCrew
                        Flight.class, // Flight включает Airplane, Airport и FlightCrew
                        Ticket.class) // Ticket включает Passenger и Flight
                .connect();

        Connection connection = cm.connection();

        // Create repositories - context of your database
        // Создание репозиториев - контекста вашей базы данных
        var airplanes = QueryRepositoryFactory.create(AirplaneRepository.class, Airplane.class, connection);
        var airports = QueryRepositoryFactory.create(AirpotsRepository.class, Airport.class, connection);
        var employees = QueryRepositoryFactory.create(EmployeeRepository.class, Employee.class, connection);
        var passengers = QueryRepositoryFactory.create(PassengerRepository.class, Passenger.class, connection);
        var flight_crews = QueryRepositoryFactory.create(FlightCrewRepository.class, FlightCrew.class, connection);
        var flights = QueryRepositoryFactory.create(FlightRepository.class, Flight.class, connection);
        var tickets = QueryRepositoryFactory.create(TicketRepository.class, Ticket.class, connection);

        // Next do something with your entities - CRUD ;)
        // Далее взаимодействуйте с вашими сущностями - CRUD операции ;)

        Airplane airplaneEntity = new Airplane();

        airplaneEntity.setManufacturer("Boeing");
        airplaneEntity.setModel("737");
        airplaneEntity.setPassCapacity(250);
        airplaneEntity.setNumber("RF00243225A");
        airplanes.save(airplaneEntity);

        airplaneEntity.setManufacturer("Airbus");
        airplaneEntity.setModel("A320");
        airplaneEntity.setPassCapacity(275);
        airplaneEntity.setNumber("RF00987354A");
        airplanes.save(airplaneEntity);

        airplaneEntity.setManufacturer("Boeing");
        airplaneEntity.setModel("777");
        airplaneEntity.setPassCapacity(340);
        airplaneEntity.setNumber("RF00549743A");
        airplanes.save(airplaneEntity);

        airplaneEntity.setManufacturer("Boeing");
        airplaneEntity.setModel("777");
        airplaneEntity.setPassCapacity(340);
        airplaneEntity.setNumber("RF00876567A");
        airplanes.save(airplaneEntity);

        airplaneEntity.setManufacturer("Boeing");
        airplaneEntity.setModel("737");
        airplaneEntity.setPassCapacity(250);
        airplaneEntity.setNumber("RF00979421A");
        airplanes.save(airplaneEntity);

        airplaneEntity.setManufacturer("Boeing");
        airplaneEntity.setModel("737");
        airplaneEntity.setPassCapacity(250);
        airplaneEntity.setNumber("RF00123729A");
        airplanes.save(airplaneEntity);

        Airport airportEntity = new Airport();

        airportEntity.setName("Domodedovo");
        airportEntity.setCode("DME");
        airportEntity.setCity("Moscow");
        airports.save(airportEntity);

        airportEntity.setName("Tolmachevo");
        airportEntity.setCode("OVB");
        airportEntity.setCity("Novosibirsk");
        airports.save(airportEntity);

        Employee employeeEntity = new Employee();

        employeeEntity.setFull_name("Ivan Barinov");
        employeeEntity.setPosition("Pilot");
        employeeEntity.setBirth_date(LocalDate.of(1970, 3, 13));
        employeeEntity.setHire_date(LocalDate.of(2003, 2, 26));
        employees.save(employeeEntity);

        employeeEntity.setFull_name("Petr Smirnov");
        employeeEntity.setPosition("Pilot");
        employeeEntity.setBirth_date(LocalDate.of(1979, 4, 7));
        employeeEntity.setHire_date(LocalDate.of(2010, 11, 20));
        employees.save(employeeEntity);

        employeeEntity.setFull_name("Sergey Andreev");
        employeeEntity.setPosition("Pilot");
        employeeEntity.setBirth_date(LocalDate.of(1976, 10, 20));
        employeeEntity.setHire_date(LocalDate.of(2006, 5, 15));
        employees.save(employeeEntity);

        employeeEntity.setFull_name("Maksim Haritonov");
        employeeEntity.setPosition("Pilot");
        employeeEntity.setBirth_date(LocalDate.of(1989, 1, 27));
        employeeEntity.setHire_date(LocalDate.of(2017, 9, 9));
        employees.save(employeeEntity);

        FlightCrew flightCrewEntity = new FlightCrew();

        employees.findByFullName("Ivan Barinov").ifPresent(flightCrewEntity::setCapitan);
        employees.findByFullName("Petr Smirnov").ifPresent(flightCrewEntity::setCo_pilot);
        flight_crews.save(flightCrewEntity);

        employees.findByFullName("Sergey Andreev").ifPresent(flightCrewEntity::setCapitan);
        employees.findByFullName("Maksim Haritonov").ifPresent(flightCrewEntity::setCo_pilot);
        flight_crews.save(flightCrewEntity);

        Passenger passengerEntity = new Passenger();

        passengerEntity.setFull_name("Alexey Grigoriev");
        passengerEntity.setBirth_date(LocalDate.of(1998, 7, 26));
        passengerEntity.setPassport_number("5018 654076");
        passengers.save(passengerEntity);

        passengerEntity.setFull_name("Oleg Borisov");
        passengerEntity.setBirth_date(LocalDate.of(1995, 4, 29));
        passengerEntity.setPassport_number("5015 458301");
        passengers.save(passengerEntity);

        passengerEntity.setFull_name("Fedor Antonov");
        passengerEntity.setBirth_date(LocalDate.of(1968, 12, 12));
        passengerEntity.setPassport_number("5013 223455");
        passengers.save(passengerEntity);

        passengerEntity.setFull_name("Denis Popov");
        passengerEntity.setBirth_date(LocalDate.of(2001, 8, 2));
        passengerEntity.setPassport_number("5021 768876");
        passengers.save(passengerEntity);

        passengerEntity.setFull_name("Roman Agapov");
        passengerEntity.setBirth_date(LocalDate.of(1994, 3, 5));
        passengerEntity.setPassport_number("5004 576954");
        passengers.save(passengerEntity);

        Flight flightEntity = new Flight();

        flightEntity.setFlight_number("243225");
        airplanes.findByNumber("RF00243225A").ifPresent(flightEntity::setAirplane);
        flight_crews.findByCapitanFullName("Ivan Barinov").ifPresent(flightEntity::setCrew);
        airports.findByCode("OVB").ifPresent(flightEntity::setArrival_airport);
        airports.findByCode("DME").ifPresent(flightEntity::setDeparture_airport);
        flightEntity.setArrival_time(LocalDateTime.of(2026, 1, 27, 12, 15));
        flightEntity.setDeparture_time(LocalDateTime.of(2026, 1, 27, 15, 55));
        flights.save(flightEntity);

        Ticket ticketEntity = new Ticket();
        passengers.findByFull_Name("Alexey Grigoriev").ifPresent(ticketEntity::setPassenger);
        flights.findByFlightNumber("243225").ifPresent(ticketEntity::setFlight);
        tickets.save(ticketEntity);
    }
}
