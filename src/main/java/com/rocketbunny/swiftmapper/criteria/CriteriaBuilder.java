package com.rocketbunny.swiftmapper.criteria;

import com.rocketbunny.swiftmapper.criteria.model.CriteriaQuery;
import com.rocketbunny.swiftmapper.utils.naming.NamingStrategy;

import java.util.ArrayList;
import java.util.List;

public class CriteriaBuilder<T> {
    private final Class<T> entityClass;
    private final List<Criterion> criteria = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private Integer limit;
    private Integer offset;

    public CriteriaBuilder(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    private void validateProperty(String property) {
        if (property == null || !property.matches("^[a-zA-Z0-9_\\.]+$")) {
            throw new IllegalArgumentException("Invalid property name to prevent SQL Injection: " + property);
        }
    }

    public CriteriaBuilder<T> equal(String property, Object value) {
        validateProperty(property);
        criteria.add(new Criterion(property, "=", value));
        return this;
    }

    public CriteriaBuilder<T> notEqual(String property, Object value) {
        validateProperty(property);
        criteria.add(new Criterion(property, "<>", value));
        return this;
    }

    public CriteriaBuilder<T> greaterThan(String property, Object value) {
        validateProperty(property);
        criteria.add(new Criterion(property, ">", value));
        return this;
    }

    public CriteriaBuilder<T> lessThan(String property, Object value) {
        validateProperty(property);
        criteria.add(new Criterion(property, "<", value));
        return this;
    }

    public CriteriaBuilder<T> like(String property, String pattern) {
        validateProperty(property);
        criteria.add(new Criterion(property, "LIKE", pattern));
        return this;
    }

    public CriteriaBuilder<T> in(String property, List<?> values) {
        validateProperty(property);
        criteria.add(new Criterion(property, "IN", values));
        return this;
    }

    public CriteriaBuilder<T> isNull(String property) {
        validateProperty(property);
        criteria.add(new Criterion(property, "IS NULL", null));
        return this;
    }

    public CriteriaBuilder<T> isNotNull(String property) {
        validateProperty(property);
        criteria.add(new Criterion(property, "IS NOT NULL", null));
        return this;
    }

    public CriteriaBuilder<T> orderByAsc(String property) {
        validateProperty(property);
        orders.add(new Order(property, "ASC"));
        return this;
    }

    public CriteriaBuilder<T> orderByDesc(String property) {
        validateProperty(property);
        orders.add(new Order(property, "DESC"));
        return this;
    }

    public CriteriaBuilder<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    public CriteriaBuilder<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    public CriteriaQuery<T> build() {
        String tableName = NamingStrategy.getTableName(entityClass);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName);
        List<Object> params = new ArrayList<>();

        if (!criteria.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < criteria.size(); i++) {
                if (i > 0) sql.append(" AND ");
                Criterion c = criteria.get(i);

                if (c.operator.equals("IN")) {
                    sql.append(c.property).append(" IN (");
                    List<?> values = (List<?>) c.value;
                    for (int j = 0; j < values.size(); j++) {
                        if (j > 0) sql.append(", ");
                        sql.append("?");
                        params.add(values.get(j));
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
                sql.append(orders.get(i).property).append(" ").append(orders.get(i).direction);
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

    private record Criterion(String property, String operator, Object value) {}
    private record Order(String property, String direction) {}
}