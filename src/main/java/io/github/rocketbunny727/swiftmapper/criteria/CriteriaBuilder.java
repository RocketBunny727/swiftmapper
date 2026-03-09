package io.github.rocketbunny727.swiftmapper.criteria;

import io.github.rocketbunny727.swiftmapper.criteria.model.BuiltQuery;
import io.github.rocketbunny727.swiftmapper.criteria.model.CriteriaQuery;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CriteriaBuilder<T> {
    private final Class<T> entityClass;
    private final List<Criterion> criteria = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private Integer limit;
    private Integer offset;
    private final SQLQueryBuilder sqlBuilder;

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TABLE",
            "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "UNION",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "HAVING",
            "ORDER", "LIMIT", "OFFSET", "EXEC", "EXECUTE", "SCRIPT", "--", "/*", "*/", ";"
    );

    private static final Set<String> VALID_OPERATORS = Set.of("=", "<>", ">", "<", ">=", "<=", "LIKE");

    public CriteriaBuilder(Class<T> entityClass) {
        this.entityClass = entityClass;
        this.sqlBuilder = new SQLQueryBuilder();
    }

    private String validateAndEscapeProperty(String property) {
        if (property == null || property.isEmpty()) {
            throw new IllegalArgumentException("Property name cannot be null or empty");
        }

        if (property.length() > 64) {
            throw new IllegalArgumentException("Property name too long: " + property);
        }

        String upper = property.toUpperCase();
        for (String keyword : SQL_KEYWORDS) {
            if (upper.contains(keyword)) {
                throw new SecurityException("SQL injection attempt detected in property: " + property);
            }
        }

        if (!property.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$")) {
            throw new IllegalArgumentException("Invalid property name format: " + property);
        }

        String[] parts = property.split("\\.");
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) escaped.append(".");
            escaped.append("\"").append(parts[i].replace("\"", "\"\"")).append("\"");
        }
        return escaped.toString();
    }

    public CriteriaBuilder<T> equal(String property, Object value) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), "=", value));
        return this;
    }

    public CriteriaBuilder<T> notEqual(String property, Object value) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), "<>", value));
        return this;
    }

    public CriteriaBuilder<T> greaterThan(String property, Object value) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), ">", value));
        return this;
    }

    public CriteriaBuilder<T> lessThan(String property, Object value) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), "<", value));
        return this;
    }

    public CriteriaBuilder<T> greaterThanOrEqual(String property, Object value) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), ">=", value));
        return this;
    }

    public CriteriaBuilder<T> lessThanOrEqual(String property, Object value) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), "<=", value));
        return this;
    }

    public CriteriaBuilder<T> like(String property, String pattern) {
        if (pattern != null) {
            validateLikePattern(pattern);
        }
        criteria.add(new Criterion(validateAndEscapeProperty(property), "LIKE", pattern));
        return this;
    }

    private void validateLikePattern(String pattern) {
        if (pattern.contains(";") || pattern.contains("--") || pattern.contains("/*")) {
            throw new SecurityException("Invalid characters in LIKE pattern");
        }
    }

    public CriteriaBuilder<T> in(String property, List<?> values) {
        if (values == null) {
            throw new IllegalArgumentException("Values list cannot be null for IN operator");
        }
        criteria.add(new Criterion(validateAndEscapeProperty(property), "IN", new ArrayList<>(values)));
        return this;
    }

    public CriteriaBuilder<T> isNull(String property) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), "IS NULL", null));
        return this;
    }

    public CriteriaBuilder<T> isNotNull(String property) {
        criteria.add(new Criterion(validateAndEscapeProperty(property), "IS NOT NULL", null));
        return this;
    }

    public CriteriaBuilder<T> orderByAsc(String property) {
        orders.add(new Order(validateAndEscapeProperty(property), "ASC"));
        return this;
    }

    public CriteriaBuilder<T> orderByDesc(String property) {
        orders.add(new Order(validateAndEscapeProperty(property), "DESC"));
        return this;
    }

    public CriteriaBuilder<T> limit(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Limit cannot be negative");
        }
        this.limit = limit;
        return this;
    }

    public CriteriaBuilder<T> offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative");
        }
        this.offset = offset;
        return this;
    }

    public CriteriaBuilder<T> page(int pageNumber, int pageSize) {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("Page number must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("Page size must be >= 1");
        }
        this.limit = pageSize;
        this.offset = (pageNumber - 1) * pageSize;
        return this;
    }

    public List<T> query(java.util.function.BiFunction<String, List<Object>, List<T>> queryFunction) {
        CriteriaQuery<T> cq = build();
        return queryFunction.apply(cq.sql(), cq.params());
    }

    public CriteriaQuery<T> build() {
        sqlBuilder.from(entityClass);
        sqlBuilder.selectAll();

        for (Criterion c : criteria) {
            addCriterionToSqlBuilder(c);
        }

        for (Order o : orders) {
            sqlBuilder.orderBy(o.property().replace("\"", ""), o.direction());
        }

        if (limit != null) {
            sqlBuilder.limit(limit);
        }
        if (offset != null) {
            sqlBuilder.offset(offset);
        }

        BuiltQuery built = sqlBuilder.build();

        return new CriteriaQuery<>(built.getSql(), built.getParams());
    }

    public CriteriaQuery<T> buildCount() {
        sqlBuilder.from(entityClass);
        BuiltQuery built = sqlBuilder.buildCount();
        return new CriteriaQuery<>(built.getSql(), built.getParams());
    }

    private void addCriterionToSqlBuilder(Criterion c) {
        String prop = c.property().replace("\"", "");

        switch (c.operator()) {
            case "=" -> sqlBuilder.where(prop, c.value());
            case "<>" -> sqlBuilder.where(prop, "<>", c.value());
            case ">" -> sqlBuilder.where(prop, ">", c.value());
            case "<" -> sqlBuilder.where(prop, "<", c.value());
            case ">=" -> sqlBuilder.where(prop, ">=", c.value());
            case "<=" -> sqlBuilder.where(prop, "<=", c.value());
            case "LIKE" -> sqlBuilder.like(prop, (String) c.value());
            case "IN" -> sqlBuilder.in(prop, (List<?>) c.value());
            case "IS NULL" -> sqlBuilder.isNull(prop);
            case "IS NOT NULL" -> sqlBuilder.isNotNull(prop);
        }
    }

    public CriteriaQuery<T> buildLegacy() {
        String tableName = NamingStrategy.getTableName(entityClass);
        String escapedTableName = escapeIdentifier(tableName);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(escapedTableName);
        List<Object> params = new ArrayList<>();

        if (!criteria.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < criteria.size(); i++) {
                if (i > 0) sql.append(" AND ");
                Criterion c = criteria.get(i);

                if (!VALID_OPERATORS.contains(c.operator) && !c.operator.startsWith("IS")) {
                    throw new SecurityException("Invalid operator: " + c.operator);
                }

                if (c.operator.equals("IN")) {
                    sql.append(c.property).append(" IN (");
                    List<?> values = (List<?>) c.value;
                    if (values.isEmpty()) {
                        sql.append("NULL");
                    } else {
                        for (int j = 0; j < values.size(); j++) {
                            if (j > 0) sql.append(", ");
                            sql.append("?");
                            params.add(values.get(j));
                        }
                    }
                    sql.append(")");
                } else if (c.value == null && (c.operator.equals("IS NULL") || c.operator.equals("IS NOT NULL"))) {
                    sql.append(c.property).append(" ").append(c.operator);
                } else {
                    sql.append(c.property).append(" ").append(c.operator).append(" ?");
                    params.add(c.value);
                }
            }
        }

        if (!orders.isEmpty()) {
            sql.append(" ORDER BY ");
            for (int i = 0; i < orders.size(); i++) {
                if (i > 0) sql.append(", ");
                Order order = orders.get(i);
                if (!order.direction.equals("ASC") && !order.direction.equals("DESC")) {
                    throw new SecurityException("Invalid sort direction: " + order.direction);
                }
                sql.append(order.property).append(" ").append(order.direction);
            }
        }

        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }

        if (offset != null) {
            sql.append(" OFFSET ").append(offset);
        }

        return new CriteriaQuery<>(sql.toString(), params);
    }

    private String escapeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record Criterion(String property, String operator, Object value) {}
    private record Order(String property, String direction) {}
}