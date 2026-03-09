package com.rocketbunny.swiftmapper.criteria.model;

public record JoinClause(String type, String table, String onCondition, String alias) {}
