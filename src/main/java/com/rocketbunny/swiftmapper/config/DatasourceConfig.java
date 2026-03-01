package com.rocketbunny.swiftmapper.config;

public record DatasourceConfig(String url, String username, String password,
                               String driverClassName, String migrationsLocation) {}