package com.rocketbunny.swiftmapper.query.model;

public record NestedField(Class<?> entityClass, String foreignKey, String primaryKey,
                          String propertyCondition, String propertyName) {}