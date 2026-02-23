Уточнения:
- Какую базу данных использовать основной? (PostgreSQL, H2, или обе?) - ОБЕ
- Нужна ли поддержка миграций/скриптов изменения схемы? - НУЖНА
- Хотите ли поддержку SQL-конструктора (Criteria API) или только method name parsing? - НУЖЕН КОНСТРУКТОР

TODO:
1. Аннотации для отношений (Relationships)
- Lazy loading прокси (требует CGLIB) - ЕСТЬ НА bytebunny
- Каскадные операции (сейчас только аннотации)
- ManyToMany inverse side

2. Улучшенный QueryMethodParser
- findByNameLike → LIKE %?%
- findByAgeGreaterThan → > ?
- findByStatusIn → IN (?)
- findByNameOrEmail → OR
- findByCreatedAtBetween → BETWEEN ? AND ?

3. Lazy Loading для отношений 

    Прокси-классы для отложенной загрузки связанных сущностей.

4. PreparedStatement кэширование

    Для производительности как в Dapper.

ПРОБЛЕМЫ:

1. При повторном запуске демонстрации, а именно при вставке информации
выводит ошибку, что id уже существует, хотя есть sequence на который должен ориентироваться
при генерации id сущности на этапе вставки.

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📦 SECTION 1: BASIC CRUD OPERATIONS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

▶ CREATE - Inserting new Airplanes...
[12:09:11] INFO Session. - Session created for Airplane
[12:09:11] INFO Session. - Saving entity: Airplane
[12:09:11] DEBUG .Session - Executing SQL: INSERT INTO airplanes (id,manufacturer,model,pass_capacity,number) VALUES (?,?,?,?,?)
❌ CRITICAL ERROR: null
java.lang.reflect.UndeclaredThrowableException
	at jdk.proxy2/jdk.proxy2.$Proxy12.save(Unknown Source)
	at demonstration.Demonstration.demonstrateCrudOperations(Demonstration.java:75)
	at demonstration.Demonstration.main(Demonstration.java:41)
Caused by: org.postgresql.util.PSQLException: ОШИБКА: повторяющееся значение ключа нарушает ограничение уникальности "airplanes_pkey"
  Подробности: Ключ "(id)=(AIRCRAFT_40000001)" уже существует.
	at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2734)
	at org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:2421)
	at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:372)
	at org.postgresql.jdbc.PgStatement.executeInternal(PgStatement.java:518)
	at org.postgresql.jdbc.PgStatement.execute(PgStatement.java:435)
	at org.postgresql.jdbc.PgPreparedStatement.executeWithFlags(PgPreparedStatement.java:196)
	at org.postgresql.jdbc.PgPreparedStatement.executeUpdate(PgPreparedStatement.java:157)
	at com.zaxxer.hikari.pool.ProxyPreparedStatement.executeUpdate(ProxyPreparedStatement.java:61)
	at com.zaxxer.hikari.pool.HikariProxyPreparedStatement.executeUpdate(HikariProxyPreparedStatement.java)
	at com.rocketbunny.swiftmapper.core.Session.executeInsert(Session.java:179)
	at com.rocketbunny.swiftmapper.core.Session.save(Session.java:279)
	at com.rocketbunny.swiftmapper.repository.query.QueryRepositoryFactory$QueryInvocationHandler.handleSave(QueryRepositoryFactory.java:171)
	at com.rocketbunny.swiftmapper.repository.query.QueryRepositoryFactory$QueryInvocationHandler.invoke(QueryRepositoryFactory.java:96)
	... 3 more

🔌 Closing connection pool...
[12:09:11] INFO ConnectionManager. - Connection pool closed
```

2. При повторном запуске демонстрации выводится WARN что FK для сущностей уже существует

```
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS airplanes(id VARCHAR(100) PRIMARY KEY, manufacturer VARCHAR(255), model VARCHAR(255), pass_capacity INTEGER, number VARCHAR(255))
[12:09:11] INFO ConnectionManager. - Created table: airplanes
[12:09:11] INFO ConnectionManager. - Created sequence airplanes_id_seq starting from 40000001 (owned by airplanes.id)
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS airports(id VARCHAR(100) PRIMARY KEY, name VARCHAR(255), code VARCHAR(255), city VARCHAR(255))
[12:09:11] INFO ConnectionManager. - Created table: airports
[12:09:11] INFO ConnectionManager. - Created sequence airports_id_seq starting from 30000001 (owned by airports.id)
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS employees(id VARCHAR(100) PRIMARY KEY, full_name VARCHAR(255), position VARCHAR(255), hire_date DATE, birth_date DATE)
[12:09:11] INFO ConnectionManager. - Created table: employees
[12:09:11] INFO ConnectionManager. - Created sequence employees_id_seq starting from 40000001 (owned by employees.id)
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS passengers(id VARCHAR(100) PRIMARY KEY, full_name VARCHAR(255), passport_number VARCHAR(255), birth_date DATE)
[12:09:11] INFO ConnectionManager. - Created table: passengers
[12:09:11] INFO ConnectionManager. - Created sequence passengers_id_seq starting from 50000001 (owned by passengers.id)
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS flight_crews(id VARCHAR(100) PRIMARY KEY, capitan_id VARCHAR(100), co_pilot_id VARCHAR(100))
[12:09:11] INFO ConnectionManager. - Created table: flight_crews
[12:09:11] INFO ConnectionManager. - Created sequence flight_crews_id_seq starting from 50000001 (owned by flight_crews.id)
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_flight_crews_capitan_id" для отношения "flight_crews" уже существует
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_flight_crews_co_pilot_id" для отношения "flight_crews" уже существует
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_flights_airplane_id" для отношения "flights" уже существует
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_flights_flight_crew_id" для отношения "flights" уже существует
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_flights_departure_airport_id" для отношения "flights" уже существует
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_flights_arrival_airport_id" для отношения "flights" уже существует
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS flights(id VARCHAR(100) PRIMARY KEY, flight_number VARCHAR(255), airplane_id VARCHAR(100), flight_crew_id VARCHAR(100), departure_airport_id VARCHAR(100), arrival_airport_id VARCHAR(100), departure_time TIMESTAMP, arrival_time TIMESTAMP)
[12:09:11] INFO ConnectionManager. - Created table: flights
[12:09:11] INFO ConnectionManager. - Created sequence flights_id_seq starting from 10000001 (owned by flights.id)
[12:09:11] INFO ConnectionManager. - Creating table: CREATE TABLE IF NOT EXISTS tickets(id VARCHAR(100) PRIMARY KEY, passenger_id VARCHAR(100), flight_id VARCHAR(100))
[12:09:11] INFO ConnectionManager. - Created table: tickets
[12:09:11] INFO ConnectionManager. - Created sequence tickets_id_seq starting from 20000001 (owned by tickets.id)
[12:09:11] INFO EntityMapper. - Cached 5 field mappings
[12:09:11] INFO EntityMapper. - Cached 0 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for Airplane
[12:09:11] INFO EntityMapper. - Cached 4 field mappings
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_tickets_passenger_id" для отношения "tickets" уже существует
[12:09:11] WARN ConnectionManager. - FK may already exist or error: ОШИБКА: ограничение "fk_tickets_flight_id" для отношения "tickets" уже существует
[12:09:11] INFO EntityMapper. - Cached 0 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for Airport
[12:09:11] INFO EntityMapper. - Cached 5 field mappings
[12:09:11] INFO EntityMapper. - Cached 0 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for Employee
[12:09:11] INFO EntityMapper. - Cached 4 field mappings
[12:09:11] INFO EntityMapper. - Cached 0 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for Passenger
[12:09:11] INFO EntityMapper. - Cached 1 field mappings
[12:09:11] INFO EntityMapper. - Cached 2 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for FlightCrew
[12:09:11] INFO EntityMapper. - Cached 4 field mappings
[12:09:11] INFO EntityMapper. - Cached 4 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for Flight
[12:09:11] INFO EntityMapper. - Cached 1 field mappings
[12:09:11] INFO EntityMapper. - Cached 2 relationship fields
[12:09:11] INFO EntityMapper. - EntityMapper initialized for Ticket
```

3. Не работает LAZY и EAGER

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⏳ SECTION 4: LAZY vs EAGER LOADING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
▶ EAGER Loading (FlightCrew loaded immediately)...
[12:08:03] INFO EntityMapper. - Cached 4 field mappings
[12:08:03] INFO EntityMapper. - Cached 4 relationship fields
[12:08:03] INFO EntityMapper. - EntityMapper initialized for Flight
[12:08:03] DEBUG .EntityMapper - Set lazy proxy for field FlightCrew.capitan
[12:08:03] DEBUG .EntityMapper - Set lazy proxy for field FlightCrew.co_pilot
✓ Flight loaded: SU-1234
✓ Accessing EAGER-loaded crew...
[12:08:03] DEBUG .ProxyFactory - Lazy loading triggered for Employee.getFull_name
[12:08:03] INFO EntityMapper. - Cached 5 field mappings
[12:08:03] INFO EntityMapper. - Cached 0 relationship fields
[12:08:03] INFO EntityMapper. - EntityMapper initialized for Employee
✓ Captain (EAGER): null

▶ LAZY Loading (Crew members loaded on demand)...
[12:08:03] DEBUG .QueryMethodParser - Parsing query method: findByFlightNumber
[12:08:03] DEBUG .QueryMethodParser - Generated SQL: SELECT t0.* FROM flights t0 WHERE t0.flight_number = ?
[12:08:03] INFO EntityMapper. - Cached 4 field mappings
[12:08:03] INFO EntityMapper. - Cached 4 relationship fields
[12:08:03] INFO EntityMapper. - EntityMapper initialized for Flight
[12:08:03] DEBUG .EntityMapper - Set lazy proxy for field FlightCrew.capitan
[12:08:03] DEBUG .EntityMapper - Set lazy proxy for field FlightCrew.co_pilot
✓ Flight loaded: SU-1234
⚡ Accessing LAZY-loaded co-pilot (triggers separate query)...
[12:08:03] DEBUG .ProxyFactory - Lazy loading triggered for Employee.getFull_name
✓ Co-pilot (LAZY): null
```

4. Выводится ошибки о SLF4J

```
╔══════════════════════════════════════════════════════════════╗
║         SWIFTMAPPER ORM FRAMEWORK DEMONSTRATION              ║
╚══════════════════════════════════════════════════════════════╝

[12:09:11] INFO ConnectionManager. - Loaded datasource config: postgres@jdbc:postgresql://localhost:5432/swiftmapper
SLF4J(W): No SLF4J providers were found.
SLF4J(W): Defaulting to no-operation (NOP) logger implementation
SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details.
```