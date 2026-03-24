# SwiftMapper

[![Maven Central](https://img.shields.io/maven-central/v/io.github.rocketbunny727/swiftmapper.svg)](https://central.sonatype.com/artifact/io.github.rocketbunny727/swiftmapper)

*Read this in other languages: [Русский](README_RUS.md)*

**SwiftMapper** is a lightweight, high-performance ORM (Object-Relational Mapping) framework for Java. Designed with simplicity and speed in mind, it provides a modern alternative to heavyweight ORM solutions while maintaining full control over your database interactions.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Quick Start](#quick-start)
- [Entity Mapping](#entity-mapping)
- [Repositories](#repositories)
- [Query Methods](#query-methods)
- [Criteria Builder API](#criteria-builder-api)
- [Transaction Management](#transaction-management)
- [Caching](#caching)
- [Lazy Loading](#lazy-loading)
- [Schema Migrations](#schema-migrations)
- [Relationship Mapping](#relationship-mapping)
- [Validation](#validation)
- [Spring Boot Integration](#spring-boot-integration)
- [Supported Databases](#supported-databases)

---

## Features

- **Lightweight & Fast** — Minimal overhead with optimized query execution
- **Annotation-Based Configuration** — Clean, declarative entity mapping
- **Lazy Loading** — Efficient proxy-based lazy loading for relationships via ByteBuddy
- **Connection Pooling** — Built-in HikariCP integration for optimal performance
- **Query Caching** — Configurable multi-level caching with Caffeine
- **Criteria API** — Type-safe programmatic query building with a fluent API
- **Transaction Support** — Programmatic and declarative transaction management with savepoint nesting
- **Validation** — Bean validation with custom annotations
- **Auto Schema Generation** — Automatic DDL creation from entity classes with `ddl-auto` modes
- **Database Migrations** — Versioned SQL migration runner
- **Statement Cache** — Per-connection prepared statement caching
- **Pretty SQL Logging** — Formatted, syntax-highlighted SQL output via `showSQL()`

---

## Requirements

- Java 17 or later
- Maven 3.6+ or Gradle 7+
- Spring Boot 3.x / 4.x
- Supported database (PostgreSQL, H2, MySQL — see [Supported Databases](#supported-databases))

---

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.rocketbunny727</groupId>
    <artifactId>swiftmapper</artifactId>
    <version>1.0.4</version>
</dependency>
```

You will also need a JDBC driver for your database:

```xml
<!-- PostgreSQL -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>

<!-- H2 (for tests) -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
    <scope>test</scope>
</dependency>
```

---

## Configuration

Create `application.yml` or `application.properties` in your classpath root.

### application.yml (full reference)

```yaml
swiftmapper:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: postgres
    password: secret
    driver-class-name: org.postgresql.Driver
    ddl-auto: update          # none | update | create | create-drop | validate

  migrations:
    location: db/migrations   # classpath folder with .sql migration files

  logging:
    level: "INFO"             # TRACE | DEBUG | INFO | WARN | ERROR | OFF
    sql: true                 # pretty-print every SQL statement via showSQL()
    transactions: true        # log transaction begin/commit/rollback
    slow-query-threshold: 1000  # ms; queries slower than this emit a WARN

  cache:
    enabled: true
    max-size: 1000            # maximum number of cached query results
    expire-minutes: 10        # TTL for cached entries
    provider-class: io.github.rocketbunny727.swiftmapper.cache.QueryCache$CaffeineCacheProvider

  pool:
    max-size: 10              # maximum pool connections
    min-idle: 5               # minimum idle connections to keep
    connection-timeout: 30000   # ms to wait for a free connection
    idle-timeout: 600000        # ms before an idle connection is evicted
    max-lifetime: 1800000       # ms before a connection is forcibly retired
    leak-detection-threshold: 60000  # ms; warn if a connection is held longer
```

> **⚠️ YAML boolean pitfall**: SnakeYAML parses `OFF`, `ON`, `YES`, `NO` as boolean values.
> Always quote the logging level when using these values:
> ```yaml
> logging:
>   level: "OFF"   # ✅ correct
>   level: OFF     # ❌ parsed as false — level will be ignored
> ```

### ddl-auto modes

| Value | Behaviour |
|---|---|
| `none` | Do nothing — schema must be managed manually |
| `update` | `CREATE TABLE IF NOT EXISTS` on startup (default) |
| `create` | Drop and recreate all tables on every startup |
| `create-drop` | Create on startup, drop on shutdown |
| `validate` | Verify that tables exist; throw if any are missing |

### application.properties (equivalent)

```properties
swiftmapper.datasource.url=jdbc:postgresql://localhost:5432/mydb
swiftmapper.datasource.username=postgres
swiftmapper.datasource.password=secret
swiftmapper.datasource.driver-class-name=org.postgresql.Driver
swiftmapper.datasource.ddl-auto=update
swiftmapper.migrations.location=db/migrations
swiftmapper.logging.level=INFO
swiftmapper.logging.sql=true
swiftmapper.logging.transactions=true
swiftmapper.logging.slow-query-threshold=1000
swiftmapper.cache.enabled=true
swiftmapper.cache.max-size=1000
swiftmapper.cache.expire-minutes=10
swiftmapper.pool.max-size=10
swiftmapper.pool.min-idle=5
swiftmapper.pool.connection-timeout=30000
swiftmapper.pool.idle-timeout=600000
swiftmapper.pool.max-lifetime=1800000
swiftmapper.pool.leak-detection-threshold=60000
```

---

## Quick Start

### 1. Create an Entity

```java
@Entity
@Table(name = "cars")
@Getter
@Setter
public class Car {

    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String model;

    @Column(name = "release_year", nullable = false)
    private int releaseYear;

    @Column(unique = true, nullable = false)
    private String vin;
}
```

### 2. Create a Repository Interface

Annotate it with `@SwiftRepository` so Spring Boot auto-configuration picks it up automatically:

```java
@SwiftRepository
public interface CarRepository extends Repository<Car, Long> {
    List<Car> findAllByBrand(String brand);
    Optional<Car> findByVin(String vin);
    List<Car> findByBrandAndModel(String brand, String model);
    List<Car> findAllByIds(List<Long> ids);
    List<Car> findByModelContaining(String pattern);
    List<Car> findByReleaseYearGreaterThan(int year);
    long countByBrand(String brand);
    void deleteByBrand(String brand);
}
```

### 3. Use the Repository

With Spring Boot auto-configuration, all `@SwiftRepository` interfaces and `@Entity` classes are discovered automatically — no manual `@Bean` definitions needed:

```java
@Service
@RequiredArgsConstructor
public class CarService {

    private final CarRepository carRepository;

    public Car registerCar(String brand, String model, int year, String vin) {
        Car car = new Car();
        car.setBrand(brand);
        car.setModel(model);
        car.setReleaseYear(year);
        car.setVin(vin);
        return carRepository.save(car);
    }

    public List<Car> getByBrand(String brand) {
        return carRepository.findAllByBrand(brand);
    }

    public Optional<Car> findByVin(String vin) {
        return carRepository.findByVin(vin);
    }
}
```

---

## Entity Mapping

### Core Annotations

| Annotation | Target | Description |
|---|---|---|
| `@Entity` | Class | Marks the class as a persistent entity |
| `@Table(name = "...")` | Class | Maps the class to a specific table name |
| `@Id` | Field | Marks the primary key field |
| `@GeneratedValue(strategy = ...)` | Field | Configures ID generation strategy |
| `@Column(...)` | Field | Customizes column mapping |
| `@Transient` | Field | Excludes the field from persistence |
| `@Lob` | Field | Maps to a large-object column (BLOB/CLOB) |
| `@Temporal(value = ...)` | Field | Maps Java time types to SQL date/time types |
| `@Index(name = "...", unique = ...)` | Field | Creates a database index on this column |
| `@ColumnDefinition(value = "...")` | Field | Provides a raw SQL column type definition |
| `@Check(value = "...")` | Class/Field | Adds a SQL CHECK constraint |

### @Column attributes

```java
@Column(
    name = "first_name",       // custom column name (default: snake_case of field name)
    nullable = false,          // NOT NULL constraint
    unique = false,            // UNIQUE constraint
    length = 100,              // VARCHAR length
    sqlType = "VARCHAR(100)"   // raw SQL type override
)
private String firstName;
```

### ID Generation Strategies

```java
// Auto-increment (GENERATED BY DEFAULT AS IDENTITY)
@Id
@GeneratedValue(strategy = Strategy.IDENTITY)
private Long id;

// Custom DB sequence
@Id
@GeneratedValue(strategy = Strategy.SEQUENCE, startValue = 1000)
private Long id;

// String prefix + sequence counter (e.g. "CAR_1001")
@Id
@GeneratedValue(strategy = Strategy.PATTERN, pattern = "CAR_", startValue = 1000)
private String id;

// Auto alpha-numeric ID
@Id
@GeneratedValue(strategy = Strategy.ALPHA)
private Long id;
```

### Supported Java Types

| Java Type | SQL Type |
|---|---|
| `String` | `VARCHAR(255)` |
| `Long` / `long` | `BIGINT` |
| `Integer` / `int` | `INTEGER` |
| `Double` / `double` | `DOUBLE PRECISION` |
| `Float` / `float` | `REAL` |
| `Boolean` / `boolean` | `BOOLEAN` |
| `LocalDateTime` | `TIMESTAMP` |
| `LocalDate` | `DATE` |
| `LocalTime` | `TIME` |
| `byte[]` | `BYTEA` |

---

## Repositories

Every repository interface must extend `Repository<T, ID>`, where `T` is the entity type and `ID` is the primary key type.

### Built-in Repository Methods

```java
T save(T entity);
List<T> saveAll(List<T> entities);
T update(T entity);
List<T> updateAll(List<T> entities);
Optional<T> findById(ID id);
boolean existsById(ID id);
List<T> findAll();
long count();
void deleteById(ID id);
void delete(T entity);
List<T> query(String sql, Object... params);
CriteriaBuilder<T> criteria();
SQLQueryBuilder sql();
```

### Custom Repository Methods

Annotate the interface with `@SwiftRepository` and define methods following the naming conventions in [Query Methods](#query-methods):

```java
@SwiftRepository
public interface ProductRepository extends Repository<Product, Long> {

    List<Product> findAllByCategory(String category);

    Optional<Product> findBySkuCode(String skuCode);

    List<Product> findByPriceGreaterThan(BigDecimal price);

    List<Product> findByNameContaining(String keyword);

    List<Product> findByCategoryAndPriceLessThan(String category, BigDecimal maxPrice);

    List<Product> findAllByIds(List<Long> ids);

    List<Product> findByStockGreaterThanOrderByPriceAsc(int minStock);

    long countByCategory(String category);

    boolean existsBySkuCode(String skuCode);

    void deleteByCategory(String category);
}
```

---

## Query Methods

SwiftMapper generates SQL at runtime by parsing method names. The method name must follow the pattern:

```
{verb}{Top/N?}By{Conditions}{OrderBy?}
```

### Verbs

| Verb | Returns | SQL |
|---|---|---|
| `find` | `Optional<T>` or `T` | `SELECT ... LIMIT 1` |
| `findAll` | `List<T>` | `SELECT ...` |
| `findFirst` | `Optional<T>` | `SELECT ... LIMIT 1` |
| `findTop{N}` | `List<T>` | `SELECT ... LIMIT N` |
| `count` | `long` | `SELECT COUNT(*)` |
| `exists` | `boolean` | `SELECT 1` |
| `delete` | `void` | `DELETE ...` |

### Condition Keywords

| Keyword | Example | SQL |
|---|---|---|
| *(none)* | `findByName(String name)` | `WHERE name = ?` |
| `Equals` | `findByNameEquals(String name)` | `WHERE name = ?` |
| `Not` / `NotEquals` / `Ne` | `findByNameNot(String name)` | `WHERE name <> ?` |
| `GreaterThan` / `Gt` | `findByAgeGreaterThan(int age)` | `WHERE age > ?` |
| `GreaterThanEquals` / `Gte` | `findByAgeGte(int age)` | `WHERE age >= ?` |
| `LessThan` / `Lt` | `findByPriceLt(BigDecimal price)` | `WHERE price < ?` |
| `LessThanEquals` / `Lte` | `findByPriceLte(BigDecimal price)` | `WHERE price <= ?` |
| `Between` | `findByAgeBetween(int from, int to)` | `WHERE age BETWEEN ? AND ?` |
| `Like` | `findByNameLike(String pattern)` | `WHERE name LIKE ?` |
| `NotLike` | `findByNameNotLike(String pattern)` | `WHERE name NOT LIKE ?` |
| `Containing` | `findByNameContaining(String s)` | `WHERE name LIKE '%s%'` |
| `StartingWith` | `findByNameStartingWith(String s)` | `WHERE name LIKE 's%'` |
| `EndingWith` | `findByNameEndingWith(String s)` | `WHERE name LIKE '%s'` |
| `In` | `findByStatusIn(List<String> statuses)` | `WHERE status IN (...)` |
| `NotIn` / `Nin` | `findByStatusNotIn(List<String> s)` | `WHERE status NOT IN (...)` |
| `Ids` | `findAllByIds(List<Long> ids)` | `WHERE id IN (...)` |
| `IsNull` / `Null` | `findByEmailNull()` | `WHERE email IS NULL` |
| `IsNotNull` / `NotNull` | `findByEmailNotNull()` | `WHERE email IS NOT NULL` |
| `True` | `findByActiveTrue()` | `WHERE active = true` |
| `False` | `findByActiveFalse()` | `WHERE active = false` |
| `Regex` | `findByNameRegex(String regex)` | `WHERE name ~ ?` |

### Combining Conditions

Use `And` and `Or` to combine multiple conditions:

```java
List<Car> findByBrandAndModel(String brand, String model);
List<Car> findByBrandOrModel(String brand, String model);
List<Car> findByBrandAndReleaseYearGreaterThan(String brand, int year);
```

### Sorting

Append `OrderBy{Field}Asc` or `OrderBy{Field}Desc` at the end of the method name:

```java
List<Car> findByBrandOrderByModelAsc(String brand);
List<Car> findAllOrderByReleaseYearDesc();
```

### Special: findAllByIds

`findAllByIds` maps to a bulk lookup by primary key using `IN`:

```java
List<Car> findAllByIds(List<Long> ids);
// → SELECT t0.* FROM "cars" t0 WHERE t0."id" IN (?, ?, ...)
```

Works with any collection type (`List<Integer>`, `Set<UUID>`, etc.) or an array.

### Return Type Rules

| Declared return type | Behaviour |
|---|---|
| `List<T>` | Returns all matching rows |
| `Optional<T>` | Adds `LIMIT 1`, wraps the first result |
| `T` (entity directly) | Adds `LIMIT 1`, returns first result or throws |
| `long` / `int` | Used with `count` verb |
| `boolean` | Used with `exists` verb |
| `void` | Used with `delete` verb |

> **Note on property names**: field names containing SQL keywords as substrings (e.g. `brand` contains `AND`, `score` contains `OR`) are handled correctly. The parser validates property names using a strict alphanumeric regex, not substring matching.

---

## Criteria Builder API

For complex dynamic queries, use the fluent `CriteriaBuilder<T>`:

```java
CriteriaBuilder<Car> cb = carRepository.criteria();

List<Car> results = cb
        .equal("brand", "Toyota")
        .greaterThan("releaseYear", 2015)
        .like("model", "Camry%")
        .orderByAsc("model")
        .limit(10)
        .offset(0)
        .query((sql, params) -> carRepository.query(sql, params.toArray()));
```

### Available Criteria Methods

```java
cb.equal("field", value)
cb.notEqual("field", value)
cb.greaterThan("field", value)
cb.greaterThanOrEqual("field", value)
cb.lessThan("field", value)
cb.lessThanOrEqual("field", value)
cb.like("field", "pattern%")
cb.in("field", List.of(v1, v2, v3))
cb.isNull("field")
cb.isNotNull("field")
cb.orderByAsc("field")
cb.orderByDesc("field")
cb.limit(20)
cb.offset(40)
cb.page(2, 20)    // page(pageNumber, pageSize) — 1-indexed
```

### Building and inspecting the query

```java
CriteriaQuery<Car> query = cb.equal("brand", "BMW").limit(5).build();
String sql     = query.sql();
List<Object> p = query.params();
List<Car> cars = carRepository.query(sql, p.toArray());

CriteriaQuery<Car> countQuery = cb.buildCount();
```

### SQLQueryBuilder (low-level)

```java
SQLQueryBuilder qb = carRepository.sql();
BuiltQuery q = qb
        .select("id", "brand", "model")
        .where("brand", "Ford")
        .and("release_year", ">", 2010)
        .orderBy("model", "ASC")
        .limit(50)
        .build();

List<Car> results = carRepository.query(q.getSql(), q.getParams().toArray());
```

---

## Transaction Management

### @SwiftTransactional (declarative, Spring Boot only)

```java
@Service
public class OrderService {

    @SwiftTransactional
    public Order createOrder(OrderDto dto) {
        // entire method runs in a single transaction
        Order order = orderRepository.save(dto.toModel());
        inventoryRepository.decreaseStock(dto.getProductId());
        return order;
    }

    @SwiftTransactional(propagation = Propagation.REQUIRES_NEW,
                        isolation = Connection.TRANSACTION_SERIALIZABLE,
                        readOnly = true)
    public List<Order> getOrders() {
        return orderRepository.findAll();
    }

    @SwiftTransactional(rollbackFor = { PaymentException.class },
                        noRollbackFor = { NotFoundException.class })
    public void processPayment(PaymentDto dto) { ... }
}
```

#### Propagation modes

| Mode | Behaviour |
|---|---|
| `REQUIRED` | Join existing transaction or start a new one (default) |
| `REQUIRES_NEW` | Always start a new independent transaction |
| `SUPPORTS` | Use existing transaction if present, otherwise non-transactional |
| `MANDATORY` | Must run within an existing transaction; throws if none |
| `NOT_SUPPORTED` | Always run non-transactionally |
| `NEVER` | Must not run within a transaction; throws if one is active |

### TransactionTemplate (programmatic, recommended)

```java
TransactionTemplate tx = new TransactionTemplate(connectionManager);

// Execute with result
Car saved = tx.execute(conn -> {
    return someDatabaseOperation(conn);
});

// Execute without result
tx.executeWithoutResult(conn -> {
    doWork1(conn);
    doWork2(conn);
});

// Custom isolation level
Car result = tx.executeWithIsolation(conn -> {
    return sensitiveOperation(conn);
}, Connection.TRANSACTION_SERIALIZABLE);
```

### Transaction (fine-grained, manual)

```java
Transaction tx = new Transaction(connectionManager);
tx.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
tx.begin();
try {
    // ... do work ...
    tx.commit();
} catch (Exception e) {
    tx.rollback();
    throw e;
}
```

### Savepoints (nested transactions)

```java
Transaction tx = new Transaction(connectionManager);
tx.begin();
try {
    doOuterWork(tx.getConnection());

    tx.executeWithinSavepoint(() -> {
        doInnerWork(tx.getConnection());
        // if this throws, only the inner work is rolled back
    });

    tx.commit();
} catch (Exception e) {
    tx.rollback();
}
```

### Retry with savepoints

```java
TransactionTemplate tx = new TransactionTemplate(connectionManager);
tx.executeWithNestedSavepoints(conn -> {
    // retried up to 3 times on transient (40xxx / 08xxx) SQL errors
    doWork(conn);
}, 3);
```

---

## Caching

SwiftMapper has a two-level cache:

- **QueryCache** — caches result lists per SQL query (uses Caffeine by default)
- **StatementCache** — caches `PreparedStatement` objects per connection

### QueryCache Configuration

```yaml
swiftmapper:
  cache:
    enabled: true
    max-size: 1000
    expire-minutes: 10
    provider-class: io.github.rocketbunny727.swiftmapper.cache.QueryCache$CaffeineCacheProvider
```

### Custom Cache Provider

Implement `QueryCache.CacheProvider` and reference your class:

```java
public class RedisQueryCacheProvider implements QueryCache.CacheProvider {
    @Override
    public Cache<String, List<?>> createCache(long maxSize, long expireMinutes) {
        // build and return a Redis-backed or custom cache
    }
}
```

```yaml
swiftmapper:
  cache:
    provider-class: com.example.RedisQueryCacheProvider
```

### Manual Cache Control

```java
QueryCache cache = session.getQueryCache();
cache.invalidate("io.example.Car:findById:42");
cache.invalidatePattern("io.example.Car:*");
cache.invalidateAll();
CacheStats stats = cache.getStats();
```

---

## Lazy Loading

SwiftMapper uses ByteBuddy to create proxy subclasses that defer relationship loading until a getter is first invoked.

### How it works

When an entity with a relationship field is loaded, SwiftMapper creates a proxy object. The first call to any getter or setter on that object triggers a database query to load the actual data. Subsequent calls use the already-loaded value.

```java
@Entity
@Table(name = "orders")
@Getter @Setter
public class Order {
    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;    // loaded lazily on first access
}
```

```java
Order order = orderRepository.findById(1L).orElseThrow();
// No customer query yet

String name = order.getCustomer().getName();
// NOW the customer is fetched from the database
```

### LazyList

Collections are wrapped in `LazyList<E>`, which implements `java.util.List`. The list loads from the database the first time any of its methods is called.

```java
List<Order> orders = customer.getOrders();
boolean alreadyLoaded = ((LazyList<Order>) orders).isLoaded();
int count = orders.size();    // triggers load if not yet loaded
```

---

## Schema Migrations

SwiftMapper includes a simple versioned migration runner. Place `.sql` files in the configured location (default: `db/migrations` on the classpath).

### File naming convention

```
V1__create_cars_table.sql
V2__add_color_column.sql
V3__create_customers_table.sql
```

Files are sorted by version number and executed in order. Each migration is recorded in an internal `swift_migrations` table. Already-executed migrations are skipped on subsequent startups.

### SQL file example

```sql
-- V1__create_cars_table.sql
CREATE TABLE IF NOT EXISTS cars (
    id      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    brand   VARCHAR(100) NOT NULL,
    model   VARCHAR(100) NOT NULL,
    vin     VARCHAR(17)  NOT NULL UNIQUE
);
```

### Disabling auto-migration

Leave `migrations.location` blank or set `ddl-auto: none`:

```yaml
swiftmapper:
  migrations:
    location:       # leave empty to skip
  datasource:
    ddl-auto: none  # skip schema generation entirely
```

---

## Relationship Mapping

### @ManyToOne

```java
@Entity
@Table(name = "orders")
@Getter @Setter
public class Order {
    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "customer_id", referencedColumnName = "id")
    private Customer customer;
}
```

### @OneToMany

```java
@Entity
@Table(name = "customers")
@Getter @Setter
public class Customer {
    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "customer")
    private List<Order> orders;
}
```

### @ManyToMany

```java
@Entity
@Table(name = "students")
@Getter @Setter
public class Student {
    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @ManyToMany
    @JoinTable(
        name = "student_courses",
        joinColumn = "student_id",
        inverseJoinColumn = "course_id"
    )
    private List<Course> courses;
}
```

### @OneToOne

```java
@Entity
@Table(name = "users")
@Getter @Setter
public class User {
    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "profile_id")
    private UserProfile profile;
}
```

---

## Validation

Use validation annotations on entity fields. SwiftMapper validates entities before any `save` or `update` operation.

```java
@Entity
@Table(name = "products")
@Getter @Setter
public class Product {
    @Id
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotBlank
    private String name;

    @Column(nullable = false)
    @Min(0)
    private BigDecimal price;

    @Column
    @Size(max = 500)
    private String description;
}
```

---

## Spring Boot Integration

SwiftMapper integrates with Spring Boot via auto-configuration. No manual `@Bean` definitions are required for the common case.

### How it works

1. SwiftMapper registers its auto-configuration via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
2. `SwiftMapperAutoConfiguration` scans for `@Entity` classes in your application packages and initialises the schema according to `ddl-auto`.
3. `SwiftRepositoryRegistrar` scans for interfaces annotated with `@SwiftRepository` and registers a `FactoryBean` for each one, making them available for injection.

### Minimal setup

All you need is a valid `application.yml` and your annotated entities and repositories:

```java
// Entity — discovered automatically
@Entity
@Table(name = "cars")
public class Car { ... }

// Repository — annotate with @SwiftRepository for auto-registration
@SwiftRepository
public interface CarRepository extends Repository<Car, Long> { ... }

// Service — inject directly
@Service
@RequiredArgsConstructor
public class CarService {
    private final CarRepository carRepository;
}
```

### Manual context (without Spring Boot)

If you are not using Spring Boot auto-configuration, create the context manually:

```java
SwiftMapperContext ctx = SwiftMapperContext.fromConfig()
        .initSchema(Car.class, Customer.class, Order.class);

CarRepository carRepo      = ctx.getRepository(CarRepository.class);
CustomerRepository custRepo = ctx.getRepository(CustomerRepository.class);

// when shutting down
ctx.close();
```

---

## Supported Databases

| Database | Status | Notes |
|---|---|---|
| **PostgreSQL 12+** | Full support | Recommended; all features supported |
| **H2 2.x** | Full support | Ideal for testing |
| **MySQL 8+** | Partial | IDENTITY strategy requires `AUTO_INCREMENT` |
| **MariaDB 10.6+** | Partial | Same as MySQL |

