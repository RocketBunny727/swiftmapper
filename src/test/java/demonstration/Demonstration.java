package demonstration;

import com.rocketbunny.swiftmapper.utils.greeting.GreetingManager;
import demonstration.model.*;
import demonstration.repostiory.*;
import com.rocketbunny.swiftmapper.core.ConnectionManager;
import com.rocketbunny.swiftmapper.core.Transaction;
import com.rocketbunny.swiftmapper.core.TransactionTemplate;
import com.rocketbunny.swiftmapper.criteria.CriteriaBuilder;
import com.rocketbunny.swiftmapper.repository.SwiftRepository;
import com.rocketbunny.swiftmapper.repository.query.QueryRepositoryFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Demonstration {

    private static ConnectionManager cm;
    private static AirplaneRepository airplanes;
    private static AirpotsRepository airports;
    private static EmployeeRepository employees;
    private static FlightCrewRepository flightCrews;
    private static FlightRepository flights;
    private static PassengerRepository passengers;
    private static TicketRepository tickets;
    private static ServiceRepository services;

    public static void main(String[] args) {
        GreetingManager.printGreeting();
        printHeader("SWIFTMAPPER ORM FRAMEWORK COMPREHENSIVE DEMONSTRATION");

        try {
            initialize();
            demonstrateSection1BasicCrud();
            demonstrateSection2EntityMappings();
            demonstrateSection3RelationshipTypes();
            demonstrateSection4LazyVsEager();
            demonstrateSection5QueryMethods();
            demonstrateSection6CriteriaBuilder();
            demonstrateSection7ManyToMany();
            demonstrateSection8CascadeOperations();
            demonstrateSection9BatchOperations();
            demonstrateSection10Transactions();
            demonstrateSection11ErrorHandling();
            demonstrateSection12AdvancedFeatures();

        } catch (Exception e) {
            System.err.println("❌ CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private static void initialize() throws SQLException {
        printSubHeader("INITIALIZATION");

        cm = ConnectionManager.fromConfig()
                .initSchema(
                        Airplane.class,
                        Airport.class,
                        Employee.class,
                        Passenger.class,
                        FlightCrew.class,
                        Flight.class,
                        Ticket.class,
                        Service.class);

        airplanes = QueryRepositoryFactory.create(AirplaneRepository.class, Airplane.class, cm);
        airports = QueryRepositoryFactory.create(AirpotsRepository.class, Airport.class, cm);
        employees = QueryRepositoryFactory.create(EmployeeRepository.class, Employee.class, cm);
        flightCrews = QueryRepositoryFactory.create(FlightCrewRepository.class, FlightCrew.class, cm);
        flights = QueryRepositoryFactory.create(FlightRepository.class, Flight.class, cm);
        passengers = QueryRepositoryFactory.create(PassengerRepository.class, Passenger.class, cm);
        tickets = QueryRepositoryFactory.create(TicketRepository.class, Ticket.class, cm);
        services = QueryRepositoryFactory.create(ServiceRepository.class, Service.class, cm);

        System.out.println("✓ All repositories initialized\n");
    }

    private static void demonstrateSection1BasicCrud() throws SQLException {
        printSection("1. BASIC CRUD OPERATIONS");

        System.out.println("▶ CREATE - Inserting new records...\n");

        Airplane boeing = new Airplane();
        boeing.setManufacturer("Boeing");
        boeing.setModel("737-800");
        boeing.setPassCapacity(189);
        boeing.setNumber("RF-BOEING-001");
        Airplane savedBoeing = airplanes.save(boeing);
        System.out.println("  ✓ Airplane created: " + savedBoeing.getId());

        Airplane airbus = new Airplane();
        airbus.setManufacturer("Airbus");
        airbus.setModel("A320neo");
        airbus.setPassCapacity(180);
        airbus.setNumber("RF-AIRBUS-001");
        Airplane savedAirbus = airplanes.save(airbus);
        System.out.println("  ✓ Airplane created: " + savedAirbus.getId());

        System.out.println("\n▶ READ - Finding by ID...");
        Optional<Airplane> found = airplanes.findById(savedBoeing.getId());
        found.ifPresent(a -> System.out.println("  ✓ Found: " + a.getManufacturer() + " " + a.getModel()));

        System.out.println("\n▶ UPDATE - Modifying entity...");
        savedBoeing.setPassCapacity(200);
        airplanes.update(savedBoeing);
        System.out.println("  ✓ Updated capacity to 200");

        System.out.println("\n▶ DELETE - Removing entity...");
        String idToDelete = savedAirbus.getId();
        airplanes.delete(idToDelete);
        System.out.println("  ✓ Deleted Airbus with ID: " + idToDelete);
        System.out.println("  ✓ Verification - exists: " + airplanes.findById(idToDelete).isPresent());

        System.out.println("\n▶ READ ALL - Listing all records...");
        List<Airplane> all = airplanes.findAll();
        System.out.println("  Total airplanes: " + all.size());
        all.forEach(a -> System.out.println("    • " + a.getId() + ": " + a.getModel()));
    }

    private static void demonstrateSection2EntityMappings() throws SQLException {
        printSection("2. ENTITY MAPPING FEATURES");

        System.out.println("▶ Pattern-based ID Generation...\n");

        Airport dme = new Airport();
        dme.setName("Domodedovo International");
        dme.setCode("DME");
        dme.setCity("Moscow");
        airports.save(dme);
        System.out.println("  ✓ Airport ID (PATTERN): " + dme.getId());

        Airport ovb = new Airport();
        ovb.setName("Tolmachevo");
        ovb.setCode("OVB");
        ovb.setCity("Novosibirsk");
        airports.save(ovb);
        System.out.println("  ✓ Airport ID (PATTERN): " + ovb.getId());

        System.out.println("\n▶ Column Mapping with snake_case...\n");

        Employee captain = new Employee();
        captain.setFull_name("Ivan Petrovich Barinov");
        captain.setPosition("Captain");
        captain.setBirth_date(LocalDate.of(1970, 3, 15));
        captain.setHire_date(LocalDate.of(2005, 6, 1));
        employees.save(captain);
        System.out.println("  ✓ Employee saved with snake_case fields");

        Employee coPilot = new Employee();
        coPilot.setFull_name("Petr Sergeevich Smirnov");
        coPilot.setPosition("First Officer");
        coPilot.setBirth_date(LocalDate.of(1985, 8, 22));
        coPilot.setHire_date(LocalDate.of(2015, 3, 10));
        employees.save(coPilot);
        System.out.println("  ✓ Employee saved with snake_case fields");

        System.out.println("\n▶ LocalDate and LocalDateTime Support...\n");

        Passenger passenger = new Passenger();
        passenger.setFull_name("Alexey Grigoriev");
        passenger.setPassport_number("5018 654076");
        passenger.setBirth_date(LocalDate.of(1990, 5, 15));
        passengers.save(passenger);
        System.out.println("  ✓ Passenger with LocalDate: " + passenger.getBirth_date());
    }

    private static void demonstrateSection3RelationshipTypes() throws SQLException {
        printSection("3. RELATIONSHIP TYPES");

        System.out.println("▶ ManyToOne (EAGER) - Flight → Airplane, Airports...\n");

        Airplane plane = airplanes.findAll().get(0);
        Airport dme = airports.findByCode("DME").orElseThrow();
        Airport ovb = airports.findByCode("OVB").orElseThrow();

        FlightCrew crew = new FlightCrew();
        Employee captain = employees.findByFullName("Ivan Petrovich Barinov").orElseThrow();
        Employee coPilot = employees.findByFullName("Petr Sergeevich Smirnov").orElseThrow();
        crew.setCapitan(captain);
        crew.setCo_pilot(coPilot);
        flightCrews.save(crew);
        System.out.println("  ✓ FlightCrew created with ManyToOne to Employees");

        Flight flight = new Flight();
        flight.setFlight_number("SU-1234");
        flight.setAirplane(plane);
        flight.setCrew(crew);
        flight.setDeparture_airport(dme);
        flight.setArrival_airport(ovb);
        flight.setDeparture_time(LocalDateTime.of(2024, 12, 25, 10, 30));
        flight.setArrival_time(LocalDateTime.of(2024, 12, 25, 16, 45));
        flights.save(flight);
        System.out.println("  ✓ Flight created with 4 ManyToOne relationships");
        System.out.println("    Route: " + flight.getDeparture_airport().getCode() +
                " → " + flight.getArrival_airport().getCode());

        System.out.println("\n▶ OneToOne - Ticket → Passenger...\n");

        Passenger passenger = passengers.findByFull_Name("Alexey Grigoriev").orElseThrow();

        Ticket ticket = new Ticket();
        ticket.setPassenger(passenger);
        ticket.setFlight(flight);
        tickets.save(ticket);
        System.out.println("  ✓ Ticket created with OneToOne to Passenger");
        System.out.println("    Passenger: " + ticket.getPassenger().getFull_name());
    }

    private static void demonstrateSection4LazyVsEager() throws SQLException {
        printSection("4. LAZY vs EAGER LOADING");

        System.out.println("▶ EAGER Loading (loaded immediately with parent)...\n");

        List<Flight> allFlights = flights.findAll();
        if (!allFlights.isEmpty()) {
            Flight flight = allFlights.get(0);
            System.out.println("  ✓ Flight loaded: " + flight.getFlight_number());
            System.out.println("  ✓ Airplane (EAGER): " + flight.getAirplane().getModel());
            System.out.println("  ✓ Departure Airport (EAGER): " + flight.getDeparture_airport().getName());
            System.out.println("  ✓ Arrival Airport (EAGER): " + flight.getArrival_airport().getName());
        }

        System.out.println("\n▶ LAZY Loading (loaded on first access)...\n");

        Optional<Flight> lazyFlight = flights.findByFlightNumber("SU-1234");
        lazyFlight.ifPresent(f -> {
            System.out.println("  ✓ Flight loaded: " + f.getFlight_number());
            System.out.println("  ⚡ Accessing LAZY-loaded captain (triggers separate query)...");
            Employee captain = f.getCrew().getCapitan();
            System.out.println("  ✓ Captain (LAZY): " + captain.getFull_name());
            System.out.println("  ⚡ Accessing LAZY-loaded co-pilot...");
            Employee coPilot = f.getCrew().getCo_pilot();
            System.out.println("  ✓ Co-pilot (LAZY): " + coPilot.getFull_name());
        });
    }

    private static void demonstrateSection5QueryMethods() throws SQLException {
        printSection("5. QUERY METHODS (DERIVED QUERIES)");

        System.out.println("▶ FindBy with exact match...\n");
        Optional<Airport> byCode = airports.findByCode("DME");
        byCode.ifPresent(a -> System.out.println("  ✓ Found by code DME: " + a.getName()));

        System.out.println("\n▶ FindBy with LIKE pattern (Containing)...\n");
        List<Airplane> boeings = airplanes.findByModelContaining("737");
        System.out.println("  ✓ Found " + boeings.size() + " airplanes with '737' in model");

        System.out.println("\n▶ FindBy with nested property...\n");
        List<Flight> fromDme = flights.findByDeparture_airportCode("DME");
        System.out.println("  ✓ Found " + fromDme.size() + " flights from DME");
        if (!fromDme.isEmpty()) {
            System.out.println("  ✓ Flight uses airplane: " + fromDme.get(0).getAirplane().getModel());
        }

        System.out.println("\n▶ FindBy with multiple conditions (And)...\n");
        List<Airplane> results = airplanes.findByManufacturer("Boeing");
        System.out.println("  ✓ Found " + results.size() + " Boeing airplanes");
    }

    private static void demonstrateSection6CriteriaBuilder() throws SQLException {
        printSection("6. CRITERIA BUILDER (PROGRAMMATIC QUERIES)");

        System.out.println("▶ Building dynamic queries with CriteriaBuilder...\n");

        CriteriaBuilder<Airplane> cb = new CriteriaBuilder<>(Airplane.class);
        var query = cb
                .equal("manufacturer", "Boeing")
                .greaterThan("pass_capacity", 100)
                .orderByAsc("model")
                .limit(10)
                .build();

        System.out.println("  Generated SQL: " + query.sql());
        System.out.println("  Parameters: " + query.params());

        SwiftRepository<Airplane, String> airplaneRepo = cm.repository(Airplane.class, String.class);
        List<Airplane> results = airplaneRepo.query(query.sql(), query.params().toArray());
        System.out.println("  ✓ Found " + results.size() + " airplanes matching criteria");
    }

    private static void demonstrateSection7ManyToMany() throws SQLException {
        printSection("7. MANY-TO-MANY RELATIONSHIP");

        System.out.println("▶ Creating Services...\n");

        Service baggage = new Service();
        baggage.setName("Extra Baggage 20kg");
        baggage.setPrice(45.50);
        services.save(baggage);
        System.out.println("  ✓ Service: " + baggage.getName() + " ($" + baggage.getPrice() + ")");

        Service lounge = new Service();
        lounge.setName("Business Lounge Access");
        lounge.setPrice(80.00);
        services.save(lounge);
        System.out.println("  ✓ Service: " + lounge.getName() + " ($" + lounge.getPrice() + ")");

        Service meal = new Service();
        meal.setName("Premium Meal");
        meal.setPrice(25.00);
        services.save(meal);
        System.out.println("  ✓ Service: " + meal.getName() + " ($" + meal.getPrice() + ")");

        System.out.println("\n▶ Attaching Services to Ticket (JoinTable)...\n");

        List<Ticket> allTickets = tickets.findAll();
        if (!allTickets.isEmpty()) {
            Ticket ticket = allTickets.get(0);

            List<Service> ticketServices = new ArrayList<>();
            ticketServices.add(baggage);
            ticketServices.add(lounge);
            ticketServices.add(meal);

            ticket.setServices(ticketServices);
            tickets.update(ticket);
            System.out.println("  ✓ Added 3 services to Ticket ID: " + ticket.getId());

            System.out.println("\n▶ Fetching Ticket with Services (EAGER ManyToMany)...\n");
            Optional<Ticket> reloaded = tickets.findById(ticket.getId());
            reloaded.ifPresent(t -> {
                System.out.println("  ✓ Ticket Passenger: " + t.getPassenger().getFull_name());
                System.out.println("  ✓ Associated Services:");
                if (t.getServices() != null) {
                    double total = 0;
                    for (Service s : t.getServices()) {
                        System.out.println("    • " + s.getName() + " ($" + s.getPrice() + ")");
                        total += s.getPrice();
                    }
                    System.out.println("  ✓ Total service cost: $" + total);
                }
            });
        }
    }

    private static void demonstrateSection8CascadeOperations() throws SQLException {
        printSection("8. CASCADE OPERATIONS");

        System.out.println("▶ CascadeType.ALL on ManyToMany...\n");

        Service vipService = new Service();
        vipService.setName("VIP Transfer");
        vipService.setPrice(150.00);

        List<Ticket> allTickets = tickets.findAll();
        if (!allTickets.isEmpty()) {
            Ticket ticket = allTickets.get(0);

            List<Service> currentServices = ticket.getServices();
            if (currentServices == null) {
                currentServices = new ArrayList<>();
            }
            currentServices.add(vipService);
            ticket.setServices(currentServices);

            tickets.update(ticket);
            System.out.println("  ✓ Service added with cascade");
        }
    }

    private static void demonstrateSection9BatchOperations() throws SQLException {
        printSection("9. BATCH OPERATIONS");

        System.out.println("▶ Batch insert (saveAll)...\n");

        List<Passenger> batchPassengers = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Passenger p = new Passenger();
            p.setFull_name("Passenger " + i);
            p.setPassport_number("PASS" + String.format("%04d", i));
            p.setBirth_date(LocalDate.of(1980 + i, i, i));
            batchPassengers.add(p);
        }

        SwiftRepository<Passenger, String> passengerRepo = cm.repository(Passenger.class, String.class);
        List<Passenger> saved = passengerRepo.saveAll(batchPassengers);
        System.out.println("  ✓ Batch saved " + saved.size() + " passengers");

        System.out.println("\n▶ Batch update (updateAll)...\n");

        for (Passenger p : saved) {
            p.setFull_name(p.getFull_name() + " (Updated)");
        }
        passengerRepo.updateAll(saved);
        System.out.println("  ✓ Batch updated " + saved.size() + " passengers");

        System.out.println("\n▶ Batch delete (deleteAll)...\n");

        List<String> idsToDelete = new ArrayList<>();
        idsToDelete.add(saved.get(0).getId());
        idsToDelete.add(saved.get(1).getId());
        passengerRepo.deleteAll(idsToDelete);
        System.out.println("  ✓ Batch deleted 2 passengers");
    }

    private static void demonstrateSection10Transactions() throws SQLException {
        printSection("10. TRANSACTION MANAGEMENT");

        System.out.println("▶ Manual Transaction (begin/commit)...\n");

        Transaction tx = new Transaction(cm);
        try {
            tx.begin();

            Airplane plane = new Airplane();
            plane.setManufacturer("Embraer");
            plane.setModel("E190");
            plane.setPassCapacity(114);
            plane.setNumber("RF-EMB-001");

            SwiftRepository<Airplane, String> airplaneRepo = cm.repository(Airplane.class, String.class);
            airplaneRepo.save(plane);

            Airport airport = new Airport();
            airport.setName("Pulkovo");
            airport.setCode("LED");
            airport.setCity("Saint Petersburg");

            SwiftRepository<Airport, String> airportRepo = cm.repository(Airport.class, String.class);
            airportRepo.save(airport);

            tx.commit();
            System.out.println("  ✓ Transaction committed");
            System.out.println("    Created airplane: " + plane.getId());
            System.out.println("    Created airport: " + airport.getId());

        } catch (Exception e) {
            tx.rollback();
            System.out.println("  ✗ Transaction rolled back: " + e.getMessage());
        }

        System.out.println("\n▶ TransactionTemplate (functional style)...\n");

        TransactionTemplate template = new TransactionTemplate(cm);

        String result = template.execute(conn -> {
            SwiftRepository<Airplane, String> repo = cm.repository(Airplane.class, String.class);
            Airplane plane = new Airplane();
            plane.setManufacturer("Bombardier");
            plane.setModel("CRJ900");
            plane.setPassCapacity(90);
            plane.setNumber("RF-BMB-001");
            repo.save(plane);
            return plane.getId();
        });

        System.out.println("  ✓ TransactionTemplate executed, created: " + result);

        System.out.println("\n▶ Transaction with Rollback on Error...\n");

        Transaction tx2 = new Transaction(cm);
        int countBefore = flights.findAll().size();
        System.out.println("  Flights before: " + countBefore);

        try {
            tx2.begin();

            Flight testFlight = new Flight();
            testFlight.setFlight_number("TEST-ROLLBACK");
            testFlight.setAirplane(airplanes.findAll().get(0));
            testFlight.setCrew(flightCrews.findAll().get(0));
            testFlight.setDeparture_airport(airports.findByCode("DME").orElseThrow());
            testFlight.setArrival_airport(airports.findByCode("OVB").orElseThrow());
            flights.save(testFlight);

            throw new RuntimeException("Simulated error!");

        } catch (Exception e) {
            tx2.rollback();
            System.out.println("  ✓ Transaction rolled back due to: " + e.getMessage());
        }

        int countAfter = flights.findAll().size();
        System.out.println("  Flights after: " + countAfter);
        System.out.println("  ✓ Rollback verified: " + (countBefore == countAfter ? "SUCCESS" : "FAILED"));
    }

    private static void demonstrateSection11ErrorHandling() throws SQLException {
        printSection("11. ERROR HANDLING");

        System.out.println("▶ Graceful handling of non-existent entity...\n");

        Optional<Airplane> notFound = airplanes.findById("NON_EXISTENT_ID_12345");
        System.out.println("  ✓ Result: " + (notFound.isPresent() ? "Found" : "Not found (expected)"));

        System.out.println("\n▶ Handling invalid query results...\n");

        try {
            Optional<Employee> result = employees.findByFullName("NonExistentNameXYZ");
            System.out.println("  ✓ Graceful result: " + (result.isPresent() ? "Found" : "Not found (expected)"));
        } catch (Exception e) {
            System.out.println("  ⚠ Error: " + e.getMessage());
        }

        System.out.println("\n▶ SQL Injection Protection...\n");

        try {
            CriteriaBuilder<Airplane> cb = new CriteriaBuilder<>(Airplane.class);
            cb.equal("manufacturer; DROP TABLE airplanes; --", "Boeing");
            System.out.println("  ✗ SQL Injection should have been blocked!");
        } catch (SecurityException e) {
            System.out.println("  ✓ SQL Injection blocked: " + e.getMessage());
        }
    }

    private static void demonstrateSection12AdvancedFeatures() throws SQLException {
        printSection("12. ADVANCED FEATURES");

        System.out.println("▶ Custom Query with SQL...\n");

        SwiftRepository<Airplane, String> airplaneRepo = cm.repository(Airplane.class, String.class);
        List<Airplane> customResults = airplaneRepo.query(
                "SELECT * FROM airplanes WHERE manufacturer = ? AND pass_capacity > ?",
                "Boeing", 100);
        System.out.println("  ✓ Custom query returned " + customResults.size() + " results");

        System.out.println("\n▶ Entity State Inspection...\n");

        List<Flight> allFlights = flights.findAll();
        System.out.println("  Total flights in database: " + allFlights.size());

        List<Airport> allAirports = airports.findAll();
        System.out.println("  Total airports in database: " + allAirports.size());

        List<Employee> allEmployees = employees.findAll();
        System.out.println("  Total employees in database: " + allEmployees.size());

        List<Ticket> allTickets = tickets.findAll();
        System.out.println("  Total tickets in database: " + allTickets.size());

        System.out.println("\n▶ Final Summary...\n");
        System.out.println("  ┌─────────────────────────────────────────┐");
        System.out.println("  │  SWIFTMAPPER DEMONSTRATION COMPLETE     │");
        System.out.println("  ├─────────────────────────────────────────┤");
        System.out.println("  │  ✓ CRUD Operations                      │");
        System.out.println("  │  ✓ Entity Mappings                      │");
        System.out.println("  │  ✓ ManyToOne (EAGER & LAZY)             │");
        System.out.println("  │  ✓ OneToOne                             │");
        System.out.println("  │  ✓ ManyToMany                           │");
        System.out.println("  │  ✓ Query Methods                        │");
        System.out.println("  │  ✓ Criteria Builder                     │");
        System.out.println("  │  ✓ Lazy vs Eager Loading                │");
        System.out.println("  │  ✓ Cascade Operations                   │");
        System.out.println("  │  ✓ Batch Operations                     │");
        System.out.println("  │  ✓ Transaction Management               │");
        System.out.println("  │  ✓ Error Handling                       │");
        System.out.println("  └─────────────────────────────────────────┘");
    }

    private static void cleanup() {
        printSubHeader("CLEANUP");
        if (cm != null) {
            System.out.println("🔌 Closing connection pool...");
            cm.close();
            System.out.println("✓ Connection pool closed\n");
        }
    }

    private static void printHeader(String text) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║ " + center(text, 60) + " ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
    }

    private static void printSection(String text) {
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📦 " + text);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
    }

    private static void printSubHeader(String text) {
        System.out.println("\n▶ " + text + "...\n");
    }

    private static String center(String text, int width) {
        if (text.length() >= width) return text;
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text + " ".repeat(width - text.length() - padding);
    }
}