CREATE TABLE IF NOT EXISTS airplanes (
    id VARCHAR(100) PRIMARY KEY,
    manufacturer VARCHAR(255) NOT NULL,
    model VARCHAR(255) NOT NULL,
    pass_capacity INTEGER NOT NULL,
    number VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS airports (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(255) NOT NULL,
    city VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS employees (
    id VARCHAR(100) PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    position VARCHAR(255) NOT NULL,
    hire_date DATE NOT NULL,
    birth_date DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS flight_crews (
    id VARCHAR(100) PRIMARY KEY,
    capitan_id VARCHAR(100) NOT NULL,
    co_pilot_id VARCHAR(100) NOT NULL,
    CONSTRAINT fk_flight_crews_capitan FOREIGN KEY (capitan_id) REFERENCES employees(id),
    CONSTRAINT fk_flight_crews_copilot FOREIGN KEY (co_pilot_id) REFERENCES employees(id)
);

CREATE TABLE IF NOT EXISTS flights (
    id VARCHAR(100) PRIMARY KEY,
    flight_number VARCHAR(255),
    airplane_id VARCHAR(100) NOT NULL,
    flight_crew_id VARCHAR(100) NOT NULL,
    departure_airport_id VARCHAR(100) NOT NULL,
    arrival_airport_id VARCHAR(100) NOT NULL,
    departure_time TIMESTAMP,
    arrival_time TIMESTAMP,
    CONSTRAINT fk_flights_airplane FOREIGN KEY (airplane_id) REFERENCES airplanes(id),
    CONSTRAINT fk_flights_crew FOREIGN KEY (flight_crew_id) REFERENCES flight_crews(id),
    CONSTRAINT fk_flights_dep_airport FOREIGN KEY (departure_airport_id) REFERENCES airports(id),
    CONSTRAINT fk_flights_arr_airport FOREIGN KEY (arrival_airport_id) REFERENCES airports(id)
);

CREATE TABLE IF NOT EXISTS passengers (
    id VARCHAR(100) PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    passport_number VARCHAR(255) NOT NULL,
    birth_date DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
    id VARCHAR(100) PRIMARY KEY,
    passenger_id VARCHAR(100) NOT NULL UNIQUE,
    flight_id VARCHAR(100) NOT NULL,
    CONSTRAINT fk_tickets_passenger FOREIGN KEY (passenger_id) REFERENCES passengers(id),
    CONSTRAINT fk_tickets_flight FOREIGN KEY (flight_id) REFERENCES flights(id)
);

CREATE TABLE IF NOT EXISTS services (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS ticket_services (
    ticket_id VARCHAR(100) NOT NULL,
    service_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (ticket_id, service_id),
    CONSTRAINT fk_ts_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_ts_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE
);