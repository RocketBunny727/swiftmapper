# SwiftMapper

[![Build Status](https://github.com/RocketBunny727/swiftmapper/workflows/SwiftMapper%20CI/CD/badge.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Coverage](https://img.shields.io/badge/coverage-80%25-brightgreen.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Maven Central](https://img.shields.io/maven-central/v/com.rocketbunny/swiftmapper.svg)](https://search.maven.org/artifact/com.rocketbunny/swiftmapper)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**SwiftMapper** — это легковесный высокопроизводительный ORM (Object-Relational Mapping) фреймворк для Java. Разработанный с акцентом на простоту и скорость, он предоставляет современную альтернативу тяжеловесным ORM-решениям, сохраняя полный контроль над взаимодействием с базой данных.

## ✨ Возможности

- **Легковесность и скорость** — минимальные накладные расходы с оптимизированным выполнением запросов
- **Конфигурация через аннотации** — чистое декларативное отображение сущностей
- **Ленивая загрузка** — эффективная загрузка связей через прокси
- **Пул соединений** — встроенная интеграция с HikariCP для оптимальной производительности
- **Кэширование запросов** — настраиваемое многоуровневое кэширование с Caffeine
- **Criteria API** — типобезопасное построение запросов с fluent API
- **Поддержка транзакций** — программное и декларативное управление транзакциями
- **Система миграций** — версионирование базы данных с проверкой контрольных сумм
- **Валидация** — валидация бинов с пользовательскими аннотациями
- **Мульти-базовость** — поддержка PostgreSQL, H2 и возможность расширения
- **Maven Central** — доступен напрямую из Maven Central Repository

## 🚀 Быстрый старт

### Зависимость Maven

```xml
<dependency>
    <groupId>com.rocketbunny</groupId>
    <artifactId>swiftmapper</artifactId>
    <version>1.0.0</version>
</dependency>
```