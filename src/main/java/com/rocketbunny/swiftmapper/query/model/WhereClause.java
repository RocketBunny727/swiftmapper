package com.rocketbunny.swiftmapper.query.model;

import java.util.List;

public record WhereClause(String sql, List<ParameterBinding> bindings) {}