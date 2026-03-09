package io.github.rocketbunny727.swiftmapper.criteria;

import io.github.rocketbunny727.swiftmapper.criteria.model.BuiltQuery;
import io.github.rocketbunny727.swiftmapper.criteria.model.JoinClause;
import io.github.rocketbunny727.swiftmapper.criteria.model.WhereCondition;
import io.github.rocketbunny727.swiftmapper.utils.naming.NamingStrategy;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SQLQueryBuilder {
    private final SwiftLogger log = SwiftLogger.getLogger(SQLQueryBuilder.class);
    private final StringBuilder sql = new StringBuilder();
    private final List<Object> params = new ArrayList<>();
    private final List<String> selectColumns = new ArrayList<>();
    private String tableName;
    private String alias = "t";
    private final List<JoinClause> joins = new ArrayList<>();
    private final List<WhereCondition> whereConditions = new ArrayList<>();
    private final List<String> groupByColumns = new ArrayList<>();
    private final List<String> orderByColumns = new ArrayList<>();
    private Integer limit;
    private Integer offset;
    private boolean forUpdate = false;
    private boolean distinct = false;

    public SQLQueryBuilder select(String... columns) {
        for (String col : columns) {
            selectColumns.add(col);
        }
        return this;
    }

    public SQLQueryBuilder selectAll() {
        selectColumns.clear();
        selectColumns.add("*");
        return this;
    }

    public SQLQueryBuilder distinct() {
        this.distinct = true;
        return this;
    }

    public SQLQueryBuilder from(String table) {
        this.tableName = table;
        return this;
    }

    public SQLQueryBuilder from(Class<?> entityClass) {
        this.tableName = NamingStrategy.getTableName(entityClass);
        return this;
    }

    public SQLQueryBuilder alias(String alias) {
        this.alias = alias;
        return this;
    }

    public SQLQueryBuilder innerJoin(String table, String onCondition) {
        joins.add(new JoinClause("INNER JOIN", table, onCondition, null));
        return this;
    }

    public SQLQueryBuilder leftJoin(String table, String onCondition) {
        joins.add(new JoinClause("LEFT JOIN", table, onCondition, null));
        return this;
    }

    public SQLQueryBuilder rightJoin(String table, String onCondition) {
        joins.add(new JoinClause("RIGHT JOIN", table, onCondition, null));
        return this;
    }

    public SQLQueryBuilder join(String table, Consumer<JoinBuilder> joinBuilder) {
        JoinBuilder builder = new JoinBuilder(table);
        joinBuilder.accept(builder);
        joins.add(new JoinClause(builder.getType(), table, builder.getOnCondition(), builder.getAlias()));
        return this;
    }

    public SQLQueryBuilder where(String column, String operator, Object value) {
        whereConditions.add(new WhereCondition(column, operator, value, "AND"));
        return this;
    }

    public SQLQueryBuilder where(String column, Object value) {
        return where(column, "=", value);
    }

    public SQLQueryBuilder where(Consumer<WhereBuilder> whereBuilder) {
        WhereBuilder builder = new WhereBuilder();
        whereBuilder.accept(builder);
        whereConditions.addAll(builder.getConditions());
        return this;
    }

    public SQLQueryBuilder and(String column, String operator, Object value) {
        whereConditions.add(new WhereCondition(column, operator, value, "AND"));
        return this;
    }

    public SQLQueryBuilder or(String column, String operator, Object value) {
        whereConditions.add(new WhereCondition(column, operator, value, "OR"));
        return this;
    }

    public SQLQueryBuilder in(String column, List<?> values) {
        whereConditions.add(new WhereCondition(column, "IN", values, "AND"));
        return this;
    }

    public SQLQueryBuilder notIn(String column, List<?> values) {
        whereConditions.add(new WhereCondition(column, "NOT IN", values, "AND"));
        return this;
    }

    public SQLQueryBuilder between(String column, Object start, Object end) {
        whereConditions.add(new WhereCondition(column, "BETWEEN", List.of(start, end), "AND"));
        return this;
    }

    public SQLQueryBuilder like(String column, String pattern) {
        whereConditions.add(new WhereCondition(column, "LIKE", pattern, "AND"));
        return this;
    }

    public SQLQueryBuilder isNull(String column) {
        whereConditions.add(new WhereCondition(column, "IS NULL", null, "AND"));
        return this;
    }

    public SQLQueryBuilder isNotNull(String column) {
        whereConditions.add(new WhereCondition(column, "IS NOT NULL", null, "AND"));
        return this;
    }

    public SQLQueryBuilder groupBy(String... columns) {
        for (String col : columns) {
            groupByColumns.add(col);
        }
        return this;
    }

    public SQLQueryBuilder orderBy(String column) {
        orderByColumns.add(column + " ASC");
        return this;
    }

    public SQLQueryBuilder orderBy(String column, String direction) {
        orderByColumns.add(column + " " + direction);
        return this;
    }

    public SQLQueryBuilder orderByDesc(String column) {
        orderByColumns.add(column + " DESC");
        return this;
    }

    public SQLQueryBuilder limit(int limit) {
        this.limit = limit;
        return this;
    }

    public SQLQueryBuilder offset(int offset) {
        this.offset = offset;
        return this;
    }

    public SQLQueryBuilder page(int page, int pageSize) {
        this.limit = pageSize;
        this.offset = (page - 1) * pageSize;
        return this;
    }

    public SQLQueryBuilder forUpdate() {
        this.forUpdate = true;
        return this;
    }

    public SQLQueryBuilder forUpdateNoWait() {
        this.forUpdate = true;
        sql.append(" FOR UPDATE NOWAIT");
        return this;
    }

    public BuiltQuery build() {
        buildSelect();
        buildFrom();
        buildJoins();
        buildWhere();
        buildGroupBy();
        buildOrderBy();
        buildLimitOffset();
        buildForUpdate();

        String finalSql = sql.toString();
        log.debug("Built SQL: {}", finalSql);

        return new BuiltQuery(finalSql, params);
    }

    public BuiltQuery buildCount() {
        sql.append("SELECT COUNT(");
        if (distinct) {
            sql.append("DISTINCT ");
        }
        if (selectColumns.isEmpty() || selectColumns.get(0).equals("*")) {
            sql.append("*");
        } else {
            sql.append(String.join(", ", selectColumns));
        }
        sql.append(")");

        buildFrom();
        buildJoins();
        buildWhere();
        buildGroupBy();

        return new BuiltQuery(sql.toString(), params);
    }

    private void buildSelect() {
        sql.append("SELECT ");
        if (distinct) {
            sql.append("DISTINCT ");
        }
        if (selectColumns.isEmpty()) {
            sql.append(alias).append(".*");
        } else {
            sql.append(String.join(", ", selectColumns));
        }
    }

    private void buildFrom() {
        sql.append(" FROM ").append(tableName);
        if (alias != null && !alias.isEmpty()) {
            sql.append(" ").append(alias);
        }
    }

    private void buildJoins() {
        for (JoinClause join : joins) {
            sql.append(" ").append(join.type())
                    .append(" ").append(join.table());
            if (join.alias() != null) {
                sql.append(" ").append(join.alias());
            }
            sql.append(" ON ").append(join.onCondition());
        }
    }

    private void buildWhere() {
        if (whereConditions.isEmpty()) return;

        sql.append(" WHERE ");
        boolean first = true;
        for (WhereCondition condition : whereConditions) {
            if (!first) {
                sql.append(" ").append(condition.logicalOperator()).append(" ");
            }
            first = false;

            buildCondition(condition);
        }
    }

    private void buildCondition(WhereCondition condition) {
        String operator = condition.operator();
        Object value = condition.value();

        sql.append(condition.column()).append(" ");

        switch (operator) {
            case "IS NULL", "IS NOT NULL" -> sql.append(operator);
            case "IN", "NOT IN" -> {
                sql.append(operator).append(" (");
                List<?> values = (List<?>) value;
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) sql.append(", ");
                    sql.append("?");
                    params.add(values.get(i));
                }
                sql.append(")");
            }
            case "BETWEEN" -> {
                sql.append("BETWEEN ? AND ?");
                List<?> values = (List<?>) value;
                params.add(values.get(0));
                params.add(values.get(1));
            }
            case "LIKE" -> {
                sql.append("LIKE ?");
                params.add(value);
            }
            default -> {
                sql.append(operator).append(" ?");
                params.add(value);
            }
        }
    }

    private void buildGroupBy() {
        if (groupByColumns.isEmpty()) return;
        sql.append(" GROUP BY ").append(String.join(", ", groupByColumns));
    }

    private void buildOrderBy() {
        if (orderByColumns.isEmpty()) return;
        sql.append(" ORDER BY ").append(String.join(", ", orderByColumns));
    }

    private void buildLimitOffset() {
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
        if (offset != null) {
            sql.append(" OFFSET ").append(offset);
        }
    }

    private void buildForUpdate() {
        if (forUpdate) {
            sql.append(" FOR UPDATE");
        }
    }

    public static class JoinBuilder {
        private String type = "INNER JOIN";
        private String table;
        private String onCondition;
        private String alias;

        public JoinBuilder(String table) {
            this.table = table;
        }

        public JoinBuilder type(String type) {
            this.type = type;
            return this;
        }

        public JoinBuilder on(String condition) {
            this.onCondition = condition;
            return this;
        }

        public JoinBuilder as(String alias) {
            this.alias = alias;
            return this;
        }

        String getType() { return type; }
        String getTable() { return table; }
        String getOnCondition() { return onCondition; }
        String getAlias() { return alias; }
    }

    public static class WhereBuilder {
        private final List<WhereCondition> conditions = new ArrayList<>();

        public WhereBuilder and(String column, String operator, Object value) {
            conditions.add(new WhereCondition(column, operator, value, "AND"));
            return this;
        }

        public WhereBuilder or(String column, String operator, Object value) {
            conditions.add(new WhereCondition(column, operator, value, "OR"));
            return this;
        }

        public WhereBuilder in(String column, List<?> values) {
            conditions.add(new WhereCondition(column, "IN", values, "AND"));
            return this;
        }

        public WhereBuilder between(String column, Object start, Object end) {
            conditions.add(new WhereCondition(column, "BETWEEN", List.of(start, end), "AND"));
            return this;
        }

        List<WhereCondition> getConditions() { return conditions; }
    }
}