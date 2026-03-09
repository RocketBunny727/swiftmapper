package io.github.rocketbunny727.swiftmapper.config;

public record DatasourceConfig(String url, String username, String password,
                               String driverClassName, String migrationsLocation) {}