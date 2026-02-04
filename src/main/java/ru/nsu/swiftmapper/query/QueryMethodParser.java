package ru.nsu.swiftmapper.query;

import ru.nsu.swiftmapper.core.EntityMapper;
import ru.nsu.swiftmapper.logger.SwiftLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryMethodParser {
    private static final SwiftLogger log = SwiftLogger.getLogger(QueryMethodParser.class);
    private final EntityMapper<?> mapper;

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(find|findAll|findFirst|findTop|count|delete|exists)(\\d*)(By)(.*)$"
    );

    private static final List<TokenPattern> CONDITION_PATTERNS = List.of(
            new TokenPattern("Between", " BETWEEN ? AND ?", 2),
            new TokenPattern("GreaterThanEqual", " >= ?", 1),
            new TokenPattern("GreaterThan", " > ?", 1),
            new TokenPattern("LessThanEqual", " <= ?", 1),
            new TokenPattern("LessThan", " < ?", 1),
            new TokenPattern("StartingWith", " LIKE ? || '%'", 1, arg -> arg + "%"),
            new TokenPattern("EndingWith", " LIKE '%' || ?", 1, arg -> "%" + arg),
            new TokenPattern("Containing", " LIKE '%' || ? || '%'", 1, arg -> "%" + arg + "%"),
            new TokenPattern("Like", " LIKE ?", 1),
            new TokenPattern("NotLike", " NOT LIKE ?", 1),
            new TokenPattern("NotIn", " NOT IN (", -1, ")", true),
            new TokenPattern("In", " IN (", -1, ")", true),
            new TokenPattern("NotNull", " IS NOT NULL", 0),
            new TokenPattern("Null", " IS NULL", 0),
            new TokenPattern("Not", " <> ?", 1),
            new TokenPattern("True", " = true", 0),
            new TokenPattern("False", " = false", 0),
            new TokenPattern("Equals", " = ?", 1)
    );

    private static final Pattern LOGICAL_PATTERN = Pattern.compile(
            "(And|Or)(?=[A-Z])"
    );

    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "OrderBy([A-Z][a-zA-Z0-9]*?)(Asc|Desc)?$"
    );

    public QueryMethodParser(EntityMapper<?> mapper) {
        this.mapper = mapper;
    }

    public ParsedQuery parse(Method method, Object[] args) {
        String methodName = method.getName();
        log.debug("Parsing query method: {}", methodName);

        Matcher matcher = METHOD_PATTERN.matcher(methodName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid query method name: " + methodName);
        }

        String operation = matcher.group(1);
        String topCount = matcher.group(2);
        String conditionsPart = matcher.group(4);

        OrderClause orderClause = parseOrderBy(conditionsPart);
        String conditionsWithoutOrder = orderClause != null
                ? conditionsPart.substring(0, conditionsPart.indexOf("OrderBy"))
                : conditionsPart;

        StringBuilder sql = new StringBuilder();
        List<ParameterBinding> bindings = new ArrayList<>();

        QueryType queryType = determineQueryType(operation);

        sql.append(buildSelectClause(queryType));
        sql.append(" FROM ").append(mapper.getTableName());

        if (!conditionsWithoutOrder.isEmpty()) {
            WhereClause whereClause = parseWhereClause(conditionsWithoutOrder, method, args);
            sql.append(" WHERE ").append(whereClause.sql());
            bindings.addAll(whereClause.bindings());
        }

        if (orderClause != null) {
            sql.append(" ORDER BY ").append(orderClause.sql());
        }

        if (operation.equals("findFirst") || (operation.equals("findTop") && !topCount.isEmpty())) {
            int limit = topCount.isEmpty() ? 1 : Integer.parseInt(topCount);
            sql.append(" LIMIT ").append(limit);
        }

        String finalSql = sql.toString();
        log.debug("Generated SQL: {}", finalSql);

        return new ParsedQuery(finalSql, bindings, queryType,
                operation.equals("findFirst") || !topCount.isEmpty());
    }

    private WhereClause parseWhereClause(String conditions, Method method, Object[] args) {
        StringBuilder sql = new StringBuilder();
        List<ParameterBinding> bindings = new ArrayList<>();

        List<String> tokens = splitConditions(conditions);

        int paramIndex = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (token.equals("And")) {
                sql.append(" AND ");
                continue;
            } else if (token.equals("Or")) {
                sql.append(" OR ");
                continue;
            }

            PropertyCondition propCond = parsePropertyCondition(token);
            String columnName = mapper.getColumnName(propCond.property());

            if (propCond.operator().params() == 0) {
                sql.append(columnName).append(propCond.operator().sql());
            } else {
                sql.append(columnName).append(propCond.operator().sql());

                int paramsNeeded = propCond.operator().params();
                if (paramsNeeded == -1) {
                    if (args[paramIndex] instanceof List<?> list) {
                        String placeholders = String.join(", ",
                                java.util.Collections.nCopies(list.size(), "?"));
                        int insertPos = sql.lastIndexOf("(");
                        sql.insert(insertPos + 1, placeholders);
                        for (Object item : list) {
                            bindings.add(new ParameterBinding(paramIndex++, item));
                        }
                    } else {
                        bindings.add(new ParameterBinding(paramIndex++, args[paramIndex]));
                    }
                } else {
                    for (int j = 0; j < paramsNeeded; j++) {
                        Object value = args[paramIndex++];
                        if (propCond.operator().transformer() != null) {
                            value = propCond.operator().transformer().apply(value);
                        }
                        bindings.add(new ParameterBinding(paramIndex - 1, value));
                    }
                }
            }
        }

        return new WhereClause(sql.toString(), bindings);
    }

    private List<String> splitConditions(String conditions) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < conditions.length(); ) {
            if (conditions.startsWith("And", i) && i + 3 < conditions.length()
                    && Character.isUpperCase(conditions.charAt(i + 3))) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
                tokens.add("And");
                i += 3;
            } else if (conditions.startsWith("Or", i) && i + 2 < conditions.length()
                    && Character.isUpperCase(conditions.charAt(i + 2))) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
                tokens.add("Or");
                i += 2;
            } else {
                current.append(conditions.charAt(i));
                i++;
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private PropertyCondition parsePropertyCondition(String token) {
        for (TokenPattern pattern : CONDITION_PATTERNS) {
            String suffix = pattern.name();
            if (token.endsWith(suffix)) {
                String property = token.substring(0, token.length() - suffix.length());
                return new PropertyCondition(property, pattern);
            }
        }

        return new PropertyCondition(token, new TokenPattern("Equals", " = ?", 1));
    }

    private OrderClause parseOrderBy(String conditions) {
        Matcher matcher = ORDER_PATTERN.matcher(conditions);
        if (!matcher.find()) {
            return null;
        }

        String property = matcher.group(1);
        String direction = matcher.group(2) != null ? matcher.group(2) : "Asc";
        String column = mapper.getColumnName(property);

        return new OrderClause(column + " " + direction.toUpperCase());
    }

    private String buildSelectClause(QueryType type) {
        return switch (type) {
            case SELECT -> "SELECT *";
            case COUNT -> "SELECT COUNT(*)";
            case DELETE -> "DELETE";
            case EXISTS -> "SELECT 1";
        };
    }

    private QueryType determineQueryType(String operation) {
        return switch (operation) {
            case "find", "findAll", "findFirst", "findTop" -> QueryType.SELECT;
            case "count" -> QueryType.COUNT;
            case "delete" -> QueryType.DELETE;
            case "exists" -> QueryType.EXISTS;
            default -> QueryType.SELECT;
        };
    }

    public record ParsedQuery(String sql, List<ParameterBinding> bindings,
                              QueryType queryType, boolean singleResult) {}
    public record ParameterBinding(int index, Object value) {}
    private record WhereClause(String sql, List<ParameterBinding> bindings) {}
    private record OrderClause(String sql) {}
    private record PropertyCondition(String property, TokenPattern operator) {}

    public enum QueryType { SELECT, COUNT, DELETE, EXISTS }

    private static class TokenPattern {
        private final String name;
        private final String sql;
        private final int params;
        private final java.util.function.Function<Object, Object> transformer;
        private final String closingBracket;
        private final boolean isVararg;

        TokenPattern(String name, String sql, int params) {
            this(name, sql, params, null, false);
        }

        TokenPattern(String name, String sql, int params,
                     java.util.function.Function<Object, Object> transformer) {
            this(name, sql, params, null, false);
        }

        TokenPattern(String name, String sql, int params, String closingBracket, boolean isVararg) {
            this.name = name;
            this.sql = sql;
            this.params = params;
            this.transformer = null;
            this.closingBracket = closingBracket;
            this.isVararg = isVararg;
        }

        public String name() { return name; }
        public String sql() { return sql + (closingBracket != null ? closingBracket : ""); }
        public int params() { return params; }
        public java.util.function.Function<Object, Object> transformer() { return transformer; }
    }
}
