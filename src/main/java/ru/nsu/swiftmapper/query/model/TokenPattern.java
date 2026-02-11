package ru.nsu.swiftmapper.query.model;

import java.util.function.Function;

public class TokenPattern {
    private final String name;
    private final String sql;
    private final int params;
    private final Function<Object, Object> transformer;
    private final String closingBracket;
    private final boolean isVararg;

    public TokenPattern(String name, String sql, int params) {
        this(name, sql, params, null, false, null);
    }

    public TokenPattern(String name, String sql, int params,
                        Function<Object, Object> transformer) {
        this(name, sql, params, transformer, false, null);
    }

    public TokenPattern(String name, String sql, int params, String closingBracket, boolean isVararg) {
        this(name, sql, params, null, isVararg, closingBracket);
    }

    public TokenPattern(String name, String sql, int params,
                        Function<Object, Object> transformer, boolean isVararg, String closingBracket) {
        this.name = name;
        this.sql = sql;
        this.params = params;
        this.transformer = transformer;
        this.closingBracket = closingBracket;
        this.isVararg = isVararg;
    }

    public String name() { return name; }
    public String sql() { return sql + (closingBracket != null ? closingBracket : ""); }
    public int params() { return params; }
    public Function<Object, Object> transformer() { return transformer; }
}