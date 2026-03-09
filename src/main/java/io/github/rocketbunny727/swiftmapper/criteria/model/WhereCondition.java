package io.github.rocketbunny727.swiftmapper.criteria.model;

public record WhereCondition(String column, String operator, Object value, String logicalOperator) {}
