# SwiftMapper

[![Build Status](https://github.com/rocketbunny/swiftmapper/workflows/Java%20CI/badge.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/rocketbunny/swiftmapper/main/.github/badges/jacoco.json)](https://github.com/rocketbunny/swiftmapper/actions)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.rocketbunny727/swiftmapper.svg)](https://central.sonatype.com/artifact/io.github.rocketbunny727/swiftmapper)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

*Читать на других языках: [English](README.md)*

**SwiftMapper** — это лёгкий, высокопроизводительный ORM-фреймворк для Java. Разработан с акцентом на простоту и скорость работы; представляет современную альтернативу громоздким ORM-решениям, сохраняя при этом полный контроль над взаимодействием с базой данных.

---

## Содержание

- [Возможности](#возможности)
- [Требования](#требования)
- [Установка](#установка)
- [Конфигурация](#конфигурация)
- [Быстрый старт](#быстрый-старт)
- [Маппинг сущностей](#маппинг-сущностей)
- [Репозитории](#репозитории)
- [Query-методы](#query-методы)
- [Criteria Builder API](#criteria-builder-api)
- [Управление транзакциями](#управление-транзакциями)
- [Кэширование](#кэширование)
- [Ленивая загрузка](#ленивая-загрузка)
- [Миграции схемы](#миграции-схемы)
- [Маппинг связей](#маппинг-связей)
- [Валидация](#валидация)
- [Интеграция со Spring Boot](#интеграция-со-spring-boot)
- [Поддерживаемые базы данных](#поддерживаемые-базы-данных)
- [Часто задаваемые вопросы](#часто-задаваемые-вопросы)

---

## Возможности

- **Лёгковесность и скорость** — минимальные накладные расходы с оптимизированным выполнением запросов
- **Конфигурация через аннотации** — чистый и декларативный маппинг сущностей
- **Ленивая загрузка** — эффективная прокси-загрузка связей через ByteBuddy
- **Пул соединений** — встроенная интеграция с HikariCP для максимальной производительности
- **Кэш запросов** — настраиваемое многоуровневое кэширование на базе Caffeine
- **Criteria API** — типобезопасное построение запросов через fluent-интерфейс
- **Поддержка транзакций** — программное и декларативное управление с поддержкой вложенных savepoint
- **Валидация** — проверка данных через аннотации
- **Автогенерация схемы** — автоматическое создание DDL из классов сущностей
- **Миграции базы данных** — запуск версионированных SQL-миграций
- **Кэш PreparedStatement** — кэширование подготовленных запросов на уровне соединения

---

## Требования

- Java 17 или новее
- Maven 3.6+ или Gradle 7+
- Поддерживаемая база данных (PostgreSQL, H2, MySQL — см. [Поддерживаемые базы данных](#поддерживаемые-базы-данных))

---

## Установка

### Maven

```xml
<dependency>
    <groupId>io.github.rocketbunny727</groupId>
    <artifactId>swiftmapper</artifactId>
    <version>1.0.2</version>
</dependency>
```

Дополнительно необходим JDBC-драйвер для вашей базы данных:

```xml
<!-- PostgreSQL -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>

<!-- H2 (для тестов) -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
    <scope>test</scope>
</dependency>
```

---

## Конфигурация

Создайте файл `application.yml` или `application.properties` в корне classpath.

### application.yml (полный справочник)

```yaml
swiftmapper:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: postgres
    password: secret
    driver-class-name: org.postgresql.Driver

  migrations:
    location: db/migrations        # папка на classpath с .sql-файлами миграций

  logging:
    level: DEBUG                   # OFF, ERROR, WARN, INFO, DEBUG
    sql: true                      # логировать каждый сгенерированный SQL
    transactions: true             # логировать begin/commit/rollback
    slow-query-threshold: 1000     # мс; запросы медленнее этого порога дают предупреждение

  cache:
    enabled: true
    max-size: 1000                 # максимальное число кэшированных результатов
    expire-minutes: 10             # TTL кэш-записей
    provider-class: com.rocketbunny.swiftmapper.cache.QueryCache$CaffeineCacheProvider

  pool:
    max-size: 10                   # максимальное число соединений в пуле
    min-idle: 5                    # минимальное число простаивающих соединений
    connection-timeout: 30000      # мс ожидания свободного соединения
    idle-timeout: 600000           # мс до вытеснения простаивающего соединения
    max-lifetime: 1800000          # мс до принудительного закрытия соединения
    leak-detection-threshold: 60000 # мс; предупреждение, если соединение держится дольше
```

### application.properties (эквивалент)

```properties
swiftmapper.datasource.url=jdbc:postgresql://localhost:5432/mydb
swiftmapper.datasource.username=postgres
swiftmapper.datasource.password=secret
swiftmapper.datasource.driver-class-name=org.postgresql.Driver
swiftmapper.migrations.location=db/migrations
swiftmapper.logging.level=DEBUG
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

## Быстрый старт

### 1. Создайте сущность

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

### 2. Создайте интерфейс репозитория

```java
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

### 3. Инициализируйте контекст

```java
@Configuration
public class SwiftMapperConfig {

    @Bean(destroyMethod = "close")
    public SwiftMapperContext swiftMapperContext() throws Exception {
        return SwiftMapperContext.fromConfig()
                .initSchema(Car.class);
    }

    @Bean
    public CarRepository carRepository(SwiftMapperContext ctx) {
        return ctx.getRepository(CarRepository.class);
    }
}
```

### 4. Используйте репозиторий

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

## Маппинг сущностей

### Основные аннотации

| Аннотация | Цель | Описание |
|---|---|---|
| `@Entity` | Класс | Помечает класс как персистентную сущность |
| `@Table(name = "...")` | Класс | Маппит класс на конкретную таблицу |
| `@Id` | Поле | Помечает поле первичного ключа |
| `@GeneratedValue(strategy = ...)` | Поле | Настраивает стратегию генерации ID |
| `@Column(...)` | Поле | Настраивает маппинг столбца |
| `@Transient` | Поле | Исключает поле из персистентности |
| `@Lob` | Поле | Маппит на BLOB/CLOB |
| `@Temporal(value = ...)` | Поле | Маппит Java-тип времени на SQL-тип |
| `@Index(name = "...", unique = ...)` | Поле | Создаёт индекс базы данных |
| `@ColumnDefinition(value = "...")` | Поле | Задаёт сырой SQL-тип столбца |
| `@Check(value = "...")` | Класс/Поле | Добавляет CHECK-ограничение |

### Атрибуты @Column

```java
@Column(
    name = "first_name",       // пользовательское имя столбца (по умолчанию: snake_case имени поля)
    nullable = false,          // ограничение NOT NULL
    unique = false,            // ограничение UNIQUE
    length = 100,              // длина VARCHAR
    sqlType = "VARCHAR(100)"   // переопределение SQL-типа
)
private String firstName;
```

### Стратегии генерации ID

```java
// Автоинкремент (GENERATED BY DEFAULT AS IDENTITY)
@Id
@GeneratedValue(strategy = Strategy.IDENTITY)
private Long id;

// Пользовательская последовательность БД
@Id
@GeneratedValue(strategy = Strategy.SEQUENCE, startValue = 1000)
private Long id;

// Строковый префикс + счётчик последовательности (например, "CAR_1001")
@Id
@GeneratedValue(strategy = Strategy.PATTERN, prefix = "CAR_", startValue = 1000)
private String id;

// Автоматический алфанумерический ID
@Id
@GeneratedValue(strategy = Strategy.ALPHA)
private Long id;
```

### Поддерживаемые Java-типы

SwiftMapper автоматически маппит следующие Java-типы на SQL:

| Java-тип | SQL-тип |
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

## Репозитории

Каждый интерфейс репозитория должен расширять `Repository<T, ID>`, где `T` — тип сущности, `ID` — тип первичного ключа.

### Встроенные методы репозитория

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

### Пользовательские методы репозитория

Объявляйте методы, следуя соглашениям об именовании из раздела [Query-методы](#query-методы):

```java
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

## Query-методы

SwiftMapper генерирует SQL во время выполнения, разбирая имена методов. Имя метода должно соответствовать шаблону:

```
{глагол}{Top/N?}By{Условия}{OrderBy?}
```

### Глаголы

| Глагол | Возвращаемый тип | SQL |
|---|---|---|
| `find` | `Optional<T>` или `T` | `SELECT ... LIMIT 1` |
| `findAll` | `List<T>` | `SELECT ...` |
| `findFirst` | `Optional<T>` | `SELECT ... LIMIT 1` |
| `findTop{N}` | `List<T>` | `SELECT ... LIMIT N` |
| `count` | `long` | `SELECT COUNT(*)` |
| `exists` | `boolean` | `SELECT 1` |
| `delete` | `void` | `DELETE ...` |

### Ключевые слова условий

| Ключевое слово | Пример | SQL |
|---|---|---|
| *(без суффикса)* | `findByName(String name)` | `WHERE name = ?` |
| `Equals` | `findByNameEquals(String name)` | `WHERE name = ?` |
| `Not` / `NotEquals` / `Ne` | `findByNameNot(String name)` | `WHERE name <> ?` |
| `GreaterThan` / `Gt` | `findByAgeGreaterThan(int age)` | `WHERE age > ?` |
| `GreaterThanEquals` / `Gte` | `findByAgeGte(int age)` | `WHERE age >= ?` |
| `LessThan` / `Lt` | `findByPriceLt(BigDecimal p)` | `WHERE price < ?` |
| `LessThanEquals` / `Lte` | `findByPriceLte(BigDecimal p)` | `WHERE price <= ?` |
| `Between` | `findByAgeBetween(int from, int to)` | `WHERE age BETWEEN ? AND ?` |
| `Like` | `findByNameLike(String pattern)` | `WHERE name LIKE ?` |
| `NotLike` | `findByNameNotLike(String pattern)` | `WHERE name NOT LIKE ?` |
| `Containing` | `findByNameContaining(String s)` | `WHERE name LIKE '%s%'` |
| `StartingWith` | `findByNameStartingWith(String s)` | `WHERE name LIKE 's%'` |
| `EndingWith` | `findByNameEndingWith(String s)` | `WHERE name LIKE '%s'` |
| `In` | `findByStatusIn(List<String> s)` | `WHERE status IN (...)` |
| `NotIn` / `Nin` | `findByStatusNotIn(List<String> s)` | `WHERE status NOT IN (...)` |
| `Ids` | `findAllByIds(List<Long> ids)` | `WHERE id IN (...)` |
| `IsNull` / `Null` | `findByEmailNull()` | `WHERE email IS NULL` |
| `IsNotNull` / `NotNull` | `findByEmailNotNull()` | `WHERE email IS NOT NULL` |
| `True` | `findByActiveTrue()` | `WHERE active = true` |
| `False` | `findByActiveFalse()` | `WHERE active = false` |
| `Regex` | `findByNameRegex(String regex)` | `WHERE name ~ ?` |

### Объединение условий

Используйте `And` и `Or` для объединения нескольких условий:

```java
List<Car> findByBrandAndModel(String brand, String model);
List<Car> findByBrandOrModel(String brand, String model);
List<Car> findByBrandAndReleaseYearGreaterThan(String brand, int year);
```

### Сортировка

Добавьте `OrderBy{Поле}Asc` или `OrderBy{Поле}Desc` в конец имени метода:

```java
List<Car> findByBrandOrderByModelAsc(String brand);
List<Car> findAllOrderByReleaseYearDesc();
```

### Специальный метод: findAllByIds

`findAllByIds` — специальное ключевое слово для массового поиска по первичному ключу через `IN`:

```java
List<Car> findAllByIds(List<Long> ids);
// → SELECT t0.* FROM "cars" t0 WHERE t0."id" IN (?, ?, ...)
```

Работает с любым типом коллекции (`List<Integer>`, `Set<UUID>` и т.д.) и массивами.

### Правила возвращаемых типов

| Объявленный тип | Поведение |
|---|---|
| `List<T>` | Возвращает все совпадающие строки |
| `Optional<T>` | Добавляет `LIMIT 1`, оборачивает первый результат |
| `T` (сущность напрямую) | Добавляет `LIMIT 1`, возвращает первый результат или бросает исключение |
| `long` / `int` | Используется с глаголом `count` |
| `boolean` | Используется с глаголом `exists` |
| `void` | Используется с глаголом `delete` |

---

## Criteria Builder API

Для сложных динамических запросов используйте fluent-интерфейс `CriteriaBuilder<T>`:

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

### Доступные методы

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
cb.page(2, 20)          // page(номерСтраницы, размерСтраницы) — нумерация с 1
```

### Построение и инспекция запроса

```java
CriteriaQuery<Car> query = cb.equal("brand", "BMW").limit(5).build();
String sql     = query.sql();
List<Object> p = query.params();
List<Car> cars = carRepository.query(sql, p.toArray());

CriteriaQuery<Car> countQuery = cb.buildCount();
```

### SQLQueryBuilder (низкоуровневый)

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

## Управление транзакциями

### TransactionTemplate (рекомендуется)

```java
TransactionTemplate tx = new TransactionTemplate(connectionManager);

// С возвращаемым значением
Car saved = tx.execute(conn -> {
    return someDatabaseOperation(conn);
});

// Без возвращаемого значения
tx.executeWithoutResult(conn -> {
    doWork1(conn);
    doWork2(conn);
});

// С конкретным уровнем изоляции
Car result = tx.executeWithIsolation(conn -> {
    return sensitiveOperation(conn);
}, Connection.TRANSACTION_SERIALIZABLE);
```

### Transaction (программный, тонкое управление)

```java
Transaction tx = new Transaction(connectionManager);
tx.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
tx.begin();
try {
    // ... работа ...
    tx.commit();
} catch (Exception e) {
    tx.rollback();
    throw e;
}
```

### Savepoint (вложенные транзакции)

```java
Transaction tx = new Transaction(connectionManager);
tx.begin();
try {
    doOuterWork(tx.getConnection());

    tx.executeWithinSavepoint(() -> {
        doInnerWork(tx.getConnection());
        // если здесь выброшено исключение — откатывается только внутренняя работа
    });

    tx.commit();
} catch (Exception e) {
    tx.rollback();
}
```

### Повторные попытки с savepoint

```java
TransactionTemplate tx = new TransactionTemplate(connectionManager);
tx.executeWithNestedSavepoints(conn -> {
    // повторяется до 3 раз при транзиентных ошибках (40xxx / 08xxx)
    doWork(conn);
}, 3);
```

---

## Кэширование

SwiftMapper имеет двухуровневый кэш:

- **QueryCache** — кэширует списки результатов по SQL-запросу (по умолчанию Caffeine)
- **StatementCache** — кэширует объекты `PreparedStatement` на уровне соединения

### Конфигурация QueryCache

```yaml
swiftmapper:
  cache:
    enabled: true
    max-size: 1000
    expire-minutes: 10
    provider-class: com.rocketbunny.swiftmapper.cache.QueryCache$CaffeineCacheProvider
```

### Пользовательский провайдер кэша

Реализуйте `QueryCache.CacheProvider` и укажите ваш класс:

```java
public class RedisQueryCacheProvider implements QueryCache.CacheProvider {
    @Override
    public Cache<String, List<?>> createCache(long maxSize, long expireMinutes) {
        // создайте и верните кэш на базе Redis или другой реализации
    }
}
```

```yaml
swiftmapper:
  cache:
    provider-class: com.example.RedisQueryCacheProvider
```

### Ручное управление кэшем

```java
QueryCache cache = session.getQueryCache();
cache.invalidate("io.example.Car:findById:42");
cache.invalidatePattern("io.example.Car:*");
cache.invalidateAll();
CacheStats stats = cache.getStats();
```

---

## Ленивая загрузка

SwiftMapper использует ByteBuddy для создания прокси-подклассов, которые откладывают загрузку связей до первого вызова геттера.

### Принцип работы

При загрузке сущности со связными полями SwiftMapper создаёт прокси-объект. Первый вызов любого геттера или сеттера на этом объекте инициирует запрос к базе данных. Последующие вызовы используют уже загруженное значение.

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
    private Customer customer;           // загружается лениво при первом обращении
}
```

```java
Order order = orderRepository.findById(1L).orElseThrow();
// запрос к customer ещё не выполнен

String name = order.getCustomer().getName();
// ТЕПЕРЬ customer загружается из базы данных
```

### LazyList

Коллекции оборачиваются в `LazyList<E>`, реализующий `java.util.List`. Список загружается из базы данных при первом вызове любого его метода.

```java
List<Order> orders = customer.getOrders();
boolean alreadyLoaded = ((LazyList<Order>) orders).isLoaded();
int count = orders.size();    // инициирует загрузку, если ещё не загружено
```

---

## Миграции схемы

SwiftMapper включает простой лаунчер версионных миграций. Разместите `.sql`-файлы в настроенной директории (по умолчанию: `db/migrations` на classpath).

### Соглашение об именовании файлов

```
V1__create_cars_table.sql
V2__add_color_column.sql
V3__create_customers_table.sql
```

Файлы сортируются по номеру версии и выполняются по порядку. Каждая миграция регистрируется во внутренней таблице `swift_migrations`. При повторном запуске уже выполненные миграции пропускаются.

### Пример SQL-файла

```sql
-- V1__create_cars_table.sql
CREATE TABLE IF NOT EXISTS cars (
    id      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    brand   VARCHAR(100) NOT NULL,
    model   VARCHAR(100) NOT NULL,
    vin     VARCHAR(17)  NOT NULL UNIQUE
);
```

### Отключение автомиграций

Оставьте `migrations.location` пустым или не указывайте его:

```yaml
swiftmapper:
  migrations:
    location:    # оставьте пустым для пропуска
```

---

## Маппинг связей

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

## Валидация

Используйте аннотации валидации на полях сущности. SwiftMapper проверяет сущности перед каждой операцией `save` и `update`.

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

## Интеграция со Spring Boot

### Конфигурационный класс

```java
@Configuration
public class SwiftMapperConfig {

    @Bean(destroyMethod = "close")
    public SwiftMapperContext swiftMapperContext() throws Exception {
        return SwiftMapperContext.fromConfig()
                .initSchema(
                        Car.class,
                        Customer.class,
                        Order.class
                );
    }

    @Bean
    public CarRepository carRepository(SwiftMapperContext ctx) {
        return ctx.getRepository(CarRepository.class);
    }

    @Bean
    public CustomerRepository customerRepository(SwiftMapperContext ctx) {
        return ctx.getRepository(CustomerRepository.class);
    }

    @Bean
    public OrderRepository orderRepository(SwiftMapperContext ctx) {
        return ctx.getRepository(OrderRepository.class);
    }
}
```

Все бины репозиториев после этого доступны для `@Autowired` / инжекции через конструктор в любом месте приложения.

---

## Поддерживаемые базы данных

| База данных | Поддержка | Примечания |
|---|---|---|
| **PostgreSQL 12+** | Полная | Рекомендуется; поддерживаются все возможности |
| **H2 2.x** | Полная | Идеально подходит для тестов |
| **MySQL 8+** | Частичная | Стратегия IDENTITY требует `AUTO_INCREMENT` |
| **MariaDB 10.6+** | Частичная | Аналогично MySQL |

---

## Часто задаваемые вопросы

**В: Поддерживает ли SwiftMapper `@Transactional` из Spring?**
О: Нет, напрямую не поддерживается. Используйте `TransactionTemplate` или класс `Transaction` программно. Интеграция со Spring `@Transactional` запланирована.

**В: Можно ли использовать SwiftMapper без Spring?**
О: Да. `SwiftMapperContext.fromConfig()` читает конфигурацию из classpath без каких-либо зависимостей от Spring. Создайте репозитории вручную и внедряйте их любым удобным способом.

**В: Как выполнить несколько запросов в одной транзакции?**
О: Используйте `TransactionTemplate.executeWithoutResult` или `Transaction.begin()` / `commit()` / `rollback()`. Передавайте один и тот же объект `Connection` во все операции.

**В: Поддерживает ли SwiftMapper постраничную навигацию?**
О: Да, через `CriteriaBuilder.page(pageNumber, pageSize)` или `limit(n).offset(m)`. Поддержка `Pageable` на уровне репозитория запланирована.

**В: Можно ли использовать произвольный SQL?**
О: Да. Используйте `repository.query(sql, params)` или `SQLQueryBuilder` для полного контроля. SwiftMapper проверяет, что SQL начинается с `SELECT` и не содержит опасных паттернов.

**В: Как отключить кэш для конкретного запроса?**
О: Вызовите `session.setCacheEnabled(false)` перед выполнением запроса или инвалидируйте конкретные ключи через `session.getQueryCache().invalidate(key)`.
