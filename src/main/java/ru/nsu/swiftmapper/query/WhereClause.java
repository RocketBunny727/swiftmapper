package ru.nsu.swiftmapper.query;

import java.util.List;

public record WhereClause(String sql, List<ParameterBinding> bindings) {}