package com.rocketbunny.swiftmapper.criteria.model;

import java.util.List;

public record BuiltQuery(String sql, List<Object> params) {
    public String getSql() { return sql; }
    public List<Object> getParams() { return params; }
    public Object[] getParamsArray() { return params.toArray(); }
}
