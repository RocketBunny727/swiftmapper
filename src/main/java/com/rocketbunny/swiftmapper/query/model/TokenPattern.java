package com.rocketbunny.swiftmapper.query.model;

import java.util.function.Function;

public record TokenPattern(String name, String sql, int params, Function<Object, Object> transformer) {
    public TokenPattern(String name, String sql, int params) {
        this(name, sql, params, null);
    }
}