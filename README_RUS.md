# SwiftMapper

[![Build Status](https://github.com/rocketbunny/swiftmapper/workflows/Java%20CI/badge.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/rocketbunny/swiftmapper/main/.github/badges/jacoco.json)](https://github.com/rocketbunny/swiftmapper/actions)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.rocketbunny727/swiftmapper.svg)](https://central.sonatype.com/artifact/io.github.rocketbunny727/swiftmapper)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

*Читать на других языках: [English](README.md)*

**SwiftMapper** это легковесный и высокопроизводительный фреймворк для объектно-реляционного отображения
(Object-Relational Mapping, ORM) для Java. Разработанный с упором на простоту и скорость, он представляет собой
современную альтернативу тяжеловесным ORM-решениям, сохраняя при этом полный контроль над взаимодействием
с базой данных.

## ✨ Features

- **Lightweight & Fast** - Минимальные накладные расходы благодаря оптимизированному выполнению запросов.
- **Annotation-Based Configuration** - Чистое декларативное сопоставление сущностей.
- **Lazy Loading** - Эффективная отложенная загрузка на основе прокси для связей.
- **Connection Pooling** - Встроенная интеграция с HikariCP для оптимальной производительности.
- **Query Caching** - Настраиваемое многоуровневое кэширование с помощью Caffeine.
- **Criteria API** - Безопасное создание запросов с помощью гибкого API.
- **Transaction Support** - Программный и декларативный подходы к управлению транзакциями.
- **Validation** - Проверка модели данных с помощью пользовательских аннотаций.

## 🚀 Быстрый старт

### Подключение через Maven

```xml
<dependency>
    <groupId>io.github.rocketbunny727</groupId>
    <artifactId>swiftmapper</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Пример использования
1. Настройте properties в файле application.yml/application.properties
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

2. Создайте модель данных для БД
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

3. Настройте репозиторий для взаимодействия
```java
public interface AirplaneRepository extends Repository<Airplane, String> {
    List<Airplane> findByManufacturer(String manufacturer);
    Optional<Airplane> findByNumber(String number);
    List<Airplane> findByModelContaining(String pattern);
}
```

4. Инициализируйте контекст через конфигурацию Spring beans
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

5. Базовые CRUD-операции
```java
Airplane boeing = new Airplane();
boeing.setManufacturer("Boeing");
boeing.setModel("737-800");
boeing.setNumber("RA-00727")

airplanes.save(boeing);                                              // saving

Optional<Airplane> found1 = airplanes.findById(boeing.getId());      // finding by default repository method
Optional<Airplane> found2 = airplanes.findByNumer("RA-00727");       // finding by generated repository method   
```

6. Настраиваемые SQL-запросы с помощью CriteriaBuilder
```java
CriteriaBuilder<Airplane> cb = new CriteriaBuilder<>(Airplane.class);
var query = cb.equal("manufacturer", "Boeing")
        .greaterThan("pass_capacity", 100)
        .orderByAsc("model")
        .limit(10)
        .build();

List<Airplane> results = airplanes.query(query.sql(), query.params().toArray());
```