# SwiftMapper

[![Build Status](https://github.com/rocketbunny/swiftmapper/workflows/SwiftMapper%20CI/CD/badge.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Coverage](https://img.shields.io/badge/coverage-80%25-brightgreen.svg)](https://github.com/rocketbunny/swiftmapper/actions)
[![Maven Central](https://img.shields.io/maven-central/v/com.rocketbunny/swiftmapper.svg)](https://search.maven.org/artifact/com.rocketbunny/swiftmapper)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**SwiftMapper** is a lightweight, high-performance ORM (Object-Relational Mapping) framework for Java. Designed with simplicity and speed in mind, it provides a modern alternative to heavyweight ORM solutions while maintaining full control over your database interactions.

## ✨ Features

- **Lightweight & Fast** - Minimal overhead with optimized query execution
- **Annotation-Based Configuration** - Clean, declarative entity mapping
- **Lazy Loading** - Efficient proxy-based lazy loading for relationships
- **Connection Pooling** - Built-in HikariCP integration for optimal performance
- **Query Caching** - Configurable multi-level caching with Caffeine
- **Criteria API** - Type-safe query building with fluent API
- **Transaction Support** - Programmatic and declarative transaction management
- **Migration System** - Versioned database migrations with checksum verification
- **Validation** - Bean validation with custom annotations
- **Multi-Database** - Support for PostgreSQL, H2, and extensible to others
- **Maven Central** - Available directly from Maven Central Repository

## 🚀 Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>com.rocketbunny</groupId>
    <artifactId>swiftmapper</artifactId>
    <version>1.0.0</version>
</dependency>
```