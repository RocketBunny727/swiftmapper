package demonstration;

import demonstration.model.*;
import demonstration.repostiory.*;
import com.rocketbunny.swiftmapper.core.ConnectionManager;
import com.rocketbunny.swiftmapper.core.Transaction;
import com.rocketbunny.swiftmapper.repository.query.QueryRepositoryFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class Demonstration {
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         SWIFTMAPPER ORM FRAMEWORK DEMONSTRATION              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        ConnectionManager cm = null;
        try {
            cm = ConnectionManager.fromConfig()
                    .initSchema(
                            Airplane.class,
                            Airport.class,
                            Employee.class,
                            Passenger.class,
                            FlightCrew.class,
                            Flight.class,
                            Ticket.class);

            var airplanes = QueryRepositoryFactory.create(AirplaneRepository.class, Airplane.class, cm);
            var airports = QueryRepositoryFactory.create(AirpotsRepository.class, Airport.class, cm);
            var employees = QueryRepositoryFactory.create(EmployeeRepository.class, Employee.class, cm);
            var passengers = QueryRepositoryFactory.create(PassengerRepository.class, Passenger.class, cm);
            var flightCrews = QueryRepositoryFactory.create(FlightCrewRepository.class, FlightCrew.class, cm);
            var flights = QueryRepositoryFactory.create(FlightRepository.class, Flight.class, cm);
            var tickets = QueryRepositoryFactory.create(TicketRepository.class, Ticket.class, cm);

            demonstrateCrudOperations(airplanes, airports, employees, passengers);
            demonstrateRelationships(airplanes, airports, employees, flightCrews, flights, passengers, tickets);
            demonstrateQueryMethods(airplanes, airports, employees, flights, passengers);
            demonstrateLazyLoading(flights);
            demonstrateErrorHandling(airplanes, employees, flights);
            demonstrateTransactions(cm, airplanes, airports);

        } catch (Exception e) {
            System.err.println("❌ CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (cm != null) {
                System.out.println("\n🔌 Closing connection pool...");
                cm.close();
            }
        }
    }

    private static void demonstrateCrudOperations(
            AirplaneRepository airplanes,
            AirpotsRepository airports,
            EmployeeRepository employees,
            PassengerRepository passengers) throws SQLException {

        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📦 SECTION 1: BASIC CRUD OPERATIONS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("▶ CREATE - Inserting new Airplanes...");
        Airplane boeing737 = new Airplane();
        boeing737.setManufacturer("Boeing");
        boeing737.setModel("737-800");
        boeing737.setPassCapacity(189);
        boeing737.setNumber("RF-BOEING-001");
        Airplane savedBoeing = airplanes.save(boeing737);
        System.out.println("   ✓ Created: " + savedBoeing);

        Airplane airbus320 = new Airplane();
        airbus320.setManufacturer("Airbus");
        airbus320.setModel("A320neo");
        airbus320.setPassCapacity(180);
        airbus320.setNumber("RF-AIRBUS-001");
        Airplane savedAirbus = airplanes.save(airbus320);
        System.out.println("   ✓ Created: " + savedAirbus);

        System.out.println("\n▶ READ - Finding by ID...");
        Optional<Airplane> found = airplanes.findById(savedBoeing.getId());
        found.ifPresentOrElse(
                a -> System.out.println("   ✓ Found by ID: " + a.getId()),
                () -> System.out.println("   ✗ Not found!")
        );

        System.out.println("\n▶ UPDATE - Modifying entity...");
        savedBoeing.setPassCapacity(200);
        airplanes.update(savedBoeing);
        System.out.println("   ✓ Updated capacity to 200");

        System.out.println("\n▶ DELETE - Removing entity...");
        String idToDelete = savedAirbus.getId();
        airplanes.delete(idToDelete);
        System.out.println("   ✓ Deleted Airbus with ID: " + idToDelete);

        Optional<Airplane> deleted = airplanes.findById(idToDelete);
        System.out.println("   ✓ Verification - exists: " + deleted.isPresent());

        System.out.println("\n▶ READ ALL - Listing all airplanes...");
        List<Airplane> allAirplanes = airplanes.findAll();
        System.out.println("   Total airplanes: " + allAirplanes.size());
        allAirplanes.forEach(a -> System.out.println("   • " + a.getManufacturer() + " " + a.getModel()));
    }

    private static void demonstrateRelationships(
            AirplaneRepository airplanes,
            AirpotsRepository airports,
            EmployeeRepository employees,
            FlightCrewRepository flightCrews,
            FlightRepository flights,
            PassengerRepository passengers,
            TicketRepository tickets) throws SQLException {

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("🔗 SECTION 2: ENTITY RELATIONSHIPS (ManyToOne, OneToOne, OneToMany)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("▶ Creating Airports...");
        Airport dme = new Airport();
        dme.setName("Domodedovo");
        dme.setCode("DME");
        dme.setCity("Moscow");
        airports.save(dme);

        Airport ovb = new Airport();
        ovb.setName("Tolmachevo");
        ovb.setCode("OVB");
        ovb.setCity("Novosibirsk");
        airports.save(ovb);
        System.out.println("   ✓ Created airports: DME and OVB");

        System.out.println("\n▶ Creating Employees (Pilots)...");
        Employee captain = new Employee();
        captain.setFull_name("Ivan Barinov");
        captain.setPosition("Captain");
        captain.setBirth_date(LocalDate.of(1970, 3, 15));
        captain.setHire_date(LocalDate.of(2005, 6, 1));
        employees.save(captain);

        Employee coPilot = new Employee();
        coPilot.setFull_name("Petr Smirnov");
        coPilot.setPosition("First Officer");
        coPilot.setBirth_date(LocalDate.of(1985, 8, 22));
        coPilot.setHire_date(LocalDate.of(2015, 3, 10));
        employees.save(coPilot);
        System.out.println("   ✓ Created employees: " + captain.getFull_name() + " and " + coPilot.getFull_name());

        System.out.println("\n▶ Creating FlightCrew (ManyToOne relationships)...");
        FlightCrew crew = new FlightCrew();
        crew.setCapitan(captain);
        crew.setCo_pilot(coPilot);
        flightCrews.save(crew);
        System.out.println("   ✓ Created crew with captain: " + crew.getCapitan().getFull_name());

        System.out.println("\n▶ Creating Flight with multiple relationships...");
        Airplane plane = airplanes.findAll().get(0);

        Flight flight = new Flight();
        flight.setFlight_number("SU-1234");
        flight.setAirplane(plane);
        flight.setCrew(crew);
        flight.setDeparture_airport(dme);
        flight.setArrival_airport(ovb);
        flight.setDeparture_time(LocalDateTime.of(2024, 12, 25, 10, 30));
        flight.setArrival_time(LocalDateTime.of(2024, 12, 25, 16, 45));
        flights.save(flight);
        System.out.println("   ✓ Created flight: " + flight.getFlight_number());
        System.out.println("   ✓ Airplane: " + flight.getAirplane().getModel());
        System.out.println("   ✓ Route: " + flight.getDeparture_airport().getCode() +
                " → " + flight.getArrival_airport().getCode());

        System.out.println("\n▶ Creating Passenger and Ticket (OneToOne relationship)...");
        Passenger passenger = new Passenger();
        passenger.setFull_name("Alexey Grigoriev");
        passenger.setPassport_number("5018 654076");
        passenger.setBirth_date(LocalDate.of(1990, 5, 15));
        passengers.save(passenger);

        Ticket ticket = new Ticket();
        ticket.setPassenger(passenger);
        ticket.setFlight(flight);
        tickets.save(ticket);
        System.out.println("   ✓ Created ticket for " + ticket.getPassenger().getFull_name() +
                " on flight " + ticket.getFlight().getFlight_number());
    }

    private static void demonstrateQueryMethods(
            AirplaneRepository airplanes,
            AirpotsRepository airports,
            EmployeeRepository employees,
            FlightRepository flights,
            PassengerRepository passengers) throws SQLException {

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("🔍 SECTION 3: QUERY METHODS (Derived Queries)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("▶ FindBy with exact match...");
        Optional<Airport> byCode = airports.findByCode("DME");
        byCode.ifPresent(a -> System.out.println("   ✓ Found airport by code DME: " + a.getName()));

        System.out.println("\n▶ FindBy with LIKE pattern...");
        List<Airplane> boeings = airplanes.findByManufacturer("Boeing");
        System.out.println("   ✓ Found " + boeings.size() + " Boeing airplanes");

        System.out.println("\n▶ FindBy with nested property (EAGER fetch)...");
        List<Flight> flightsFromDme = flights.findByDeparture_airportCode("DME");
        System.out.println("   ✓ Found " + flightsFromDme.size() + " flights from DME");
        if (!flightsFromDme.isEmpty()) {
            Flight f = flightsFromDme.get(0);
            System.out.println("   ✓ Flight " + f.getFlight_number() +
                    " uses airplane " + f.getAirplane().getModel());
        }

        System.out.println("\n▶ FindBy with multiple conditions...");
        Optional<Employee> pilot = employees.findByFullName("Ivan Barinov");
        pilot.ifPresent(p -> System.out.println("   ✓ Found pilot: " + p.getFull_name() +
                " (Position: " + p.getPosition() + ")"));

        System.out.println("\n▶ Custom query with @Query-like method...");
        List<Passenger> allPassengers = passengers.findAll();
        System.out.println("   ✓ Total passengers in system: " + allPassengers.size());
    }

    private static void demonstrateLazyLoading(FlightRepository flights) throws SQLException {
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("⏳ SECTION 4: LAZY vs EAGER LOADING");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("▶ EAGER Loading (FlightCrew loaded immediately)...");
        List<Flight> allFlights = flights.findAll();
        if (!allFlights.isEmpty()) {
            Flight flight = allFlights.get(0);
            System.out.println("   ✓ Flight loaded: " + flight.getFlight_number());

            System.out.println("   ✓ Accessing EAGER-loaded crew...");
            FlightCrew crew = flight.getCrew();
            System.out.println("   ✓ Captain (EAGER): " + crew.getCapitan().getFull_name());
        }

        System.out.println("\n▶ LAZY Loading (Crew members loaded on demand)...");
        Optional<Flight> lazyFlight = flights.findByFlightNumber("SU-1234");
        lazyFlight.ifPresent(f -> {
            System.out.println("   ✓ Flight loaded: " + f.getFlight_number());
            System.out.println("   ⚡ Accessing LAZY-loaded co-pilot (triggers separate query)...");
            Employee coPilot = f.getCrew().getCo_pilot();
            System.out.println("   ✓ Co-pilot (LAZY): " + coPilot.getFull_name());
        });
    }

    private static void demonstrateErrorHandling(
            AirplaneRepository airplanes,
            EmployeeRepository employees,
            FlightRepository flights) {

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("⚠️  SECTION 5: ERROR HANDLING");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("▶ Attempting to find non-existent entity...");
        try {
            Optional<Airplane> notFound = airplanes.findById("NON_EXISTENT_ID");
            System.out.println("   ✓ Graceful handling: " + (notFound.isPresent() ? "Found" : "Not found"));
        } catch (Exception e) {
            System.out.println("   ✗ Unexpected error: " + e.getMessage());
        }

        System.out.println("\n▶ Attempting invalid query method...");
        try {
            Optional<Employee> result = employees.findByFullName("NonExistent");
            System.out.println("   ✓ Graceful handling: " + (result.isPresent() ? "Found" : "Not found"));
        } catch (Exception e) {
            System.out.println("   ✗ Error: " + e.getMessage());
        }

        System.out.println("\n▶ Attempting to access nested property that doesn't exist...");
        try {
            List<Flight> results = flights.findByDeparture_airportCode("XXX");
            System.out.println("   ✓ Query executed, results: " + results.size());
        } catch (Exception e) {
            System.out.println("   ⚠️  Expected behavior: " + e.getClass().getSimpleName());
        }
    }

    private static void demonstrateTransactions(
            ConnectionManager cm,
            AirplaneRepository airplanes,
            AirpotsRepository airports) {

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("💰 SECTION 6: TRANSACTION MANAGEMENT");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("▶ Successful transaction...");
        Transaction tx = new Transaction(cm);
        try {
            tx.begin();

            Airplane plane = new Airplane();
            plane.setManufacturer("Embraer");
            plane.setModel("E190");
            plane.setPassCapacity(114);
            plane.setNumber("RF-EMB-001");
            airplanes.save(plane);

            Airport airport = new Airport();
            airport.setName("Pulkovo");
            airport.setCode("LED");
            airport.setCity("Saint Petersburg");
            airports.save(airport);

            tx.commit();
            System.out.println("   ✓ Transaction committed successfully");
            System.out.println("   ✓ Created airplane: " + plane.getId());
            System.out.println("   ✓ Created airport: " + airport.getId());

        } catch (Exception e) {
            try {
                tx.rollback();
                System.out.println("   ✗ Transaction rolled back: " + e.getMessage());
            } catch (SQLException ex) {
                System.err.println("   ✗ Rollback failed: " + ex.getMessage());
            }
        }

        System.out.println("\n▶ Failed transaction (simulated)...");
        Transaction tx2 = new Transaction(cm);
        try {
            tx2.begin();

            Airplane plane = new Airplane();
            plane.setManufacturer("Invalid");
            plane.setModel(null);
            plane.setPassCapacity(-1);
            plane.setNumber("INVALID");
            airplanes.save(plane);

            System.out.println("   ✗ This should not print!");

        } catch (Exception e) {
            try {
                tx2.rollback();
                System.out.println("   ✓ Transaction rolled back due to error");
                System.out.println("   ✓ Error type: " + e.getClass().getSimpleName());
            } catch (SQLException ex) {
                System.err.println("   ✗ Rollback failed: " + ex.getMessage());
            }
        }

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✅ DEMONSTRATION COMPLETED SUCCESSFULLY");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}