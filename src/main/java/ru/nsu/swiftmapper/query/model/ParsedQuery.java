package ru.nsu.swiftmapper.query.model;

import java.util.List;

public record ParsedQuery(String sql, List<ParameterBinding> bindings,
                          QueryType queryType, boolean singleResult) {}