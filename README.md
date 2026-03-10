# SwiftMapper

[![Build Status](https://github.com/rocketbunny/swiftmapper/workflows/Java%20CI/badge.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/rocketbunny/swiftmapper/main/.github/badges/jacoco.json)](https://github.com/rocketbunny/swiftmapper/actions)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.rocketbunny727/swiftmapper.svg)](https://central.sonatype.com/artifact/io.github.rocketbunny727/swiftmapper)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

*Read this in other languages: [Русский](README_RUS.md)*

**SwiftMapper** is a lightweight, high-performance ORM (Object-Relational Mapping) framework for Java. Designed with simplicity and speed in mind, it provides a modern alternative to heavyweight ORM solutions while maintaining full control over your database interactions.

## ✨ Features

- **Lightweight & Fast** - Minimal overhead with optimized query execution.
- **Annotation-Based Configuration** - Clean, declarative entity mapping.
- **Lazy Loading** - Efficient proxy-based lazy loading for relationships.
- **Connection Pooling** - Built-in HikariCP integration for optimal performance.
- **Query Caching** - Configurable multi-level caching with Caffeine.
- **Criteria API** - Type-safe query building with a fluent API.
- **Transaction Support** - Programmatic and declarative transaction management.
- **Validation** - Bean validation with custom annotations.

## 🚀 Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.rocketbunny727</groupId>
    <artifactId>swiftmapper</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic Usage
1. Setup properties in application.yml/application.properties
```yml
swiftmapper:
  datasource:
    url: jdbc:postgresql://localhost:5432/swift_test_db
    username: postgres
    password: 471979
    driver-class-name: org.postgresql.Driver

  migrations:
    location: db/migrations

  logging:
    level: DEBUG
    sql: true
    transactions: true
    slow-query-threshold: 1000

  cache:
    enabled: true
    max-size: 1000
    expire-minutes: 10
    provider-class: com.rocketbunny.swiftmapper.cache.QueryCache$CaffeineCacheProvider

  pool:
    max-size: 10
    min-idle: 5
    connection-timeout: 30000
    idle-timeout: 600000
    max-lifetime: 1800000
    leak-detection-threshold: 60000
```

2. Create Entity class
```java
@Entity                                                              // mark class as entity
@Table(name = "airplanes")                                           // name of BD table
@Getter
@Setter
public class Airplane {
    @Id                                                              // mark field as table primary key
    @GeneratedValue(strategy = Strategy.IDENTITY)
    private String id;

    @Column(nullable = false)                                        // nullable and other constraints
    private String manufacturer;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false, name = "pass_capacity")
    private int PassCapacity;

    @Column(nullable = false)
    private String number;
}
```

3. Create Repository
```java
public interface AirplaneRepository extends Repository<Airplane, String> {
    List<Airplane> findByManufacturer(String manufacturer);
    Optional<Airplane> findByNumber(String number);
    List<Airplane> findByModelContaining(String pattern);
}
```

4. Initialize Context
```java
@Configuration                                                       // Configure Spring Boot beans
public class SwiftMapperConfig {

    @Bean(destroyMethod = "close")
    public SwiftMapperContext swiftMapperContext() throws Exception {
        return SwiftMapperContext.fromConfig()
                .initSchema(Airplane.class);                         // all @Entity classes
    }

    @Bean                                                            // all repositories you need
    public AirplaneRepository airplanes(SwiftMapperContext ctx) {
        return ctx.getRepository(AirplaneRepository.class);
    }
}
```

5. Basic CRUD
```java
Airplane boeing = new Airplane();
boeing.setManufacturer("Boeing");
boeing.setModel("737-800");
boeing.setNumber("RA-00727")

airplanes.save(boeing);                                              // saving

Optional<Airplane> found1 = airplanes.findById(boeing.getId());      // finding by default repository method
Optional<Airplane> found2 = airplanes.findByNumer("RA-00727");       // finding by generated repository method   
```

6. Programmatic Queries (Criteria Builder)
```java
CriteriaBuilder<Airplane> cb = new CriteriaBuilder<>(Airplane.class);
var query = cb.equal("manufacturer", "Boeing")
              .greaterThan("pass_capacity", 100)
              .orderByAsc("model")
              .limit(10)
              .build();

List<Airplane> results = airplanes.query(query.sql(), query.params().toArray());
```