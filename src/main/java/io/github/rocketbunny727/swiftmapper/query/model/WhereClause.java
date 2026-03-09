package io.github.rocketbunny727.swiftmapper.query.model;

import java.util.List;

public record WhereClause(String sql, List<ParameterBinding> bindings) {}