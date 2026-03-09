package com.rocketbunny.swiftmapper.criteria.model;

import java.util.List;

public record CriteriaQuery<T>(String sql, List<Object> params) {}