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
            new TokenPattern("GreaterThanEquals", " >= ?", 1),
            new TokenPattern("GreaterThanEqual", " >= ?", 1),
            new TokenPattern("Gte", " >= ?", 1),
            new TokenPattern("GTE", " >= ?", 1),
            new TokenPattern("GreaterThan", " > ?", 1),
            new TokenPattern("Gt", " > ?", 1),
            new TokenPattern("GT", " >= ?", 1),
            new TokenPattern("LessThanEquals", " <= ?", 1),
            new TokenPattern("LessThanEqual", " <= ?", 1),
            new TokenPattern("Lte", " <= ?", 1),
            new TokenPattern("LTE", " >= ?", 1),
            new TokenPattern("LessThan", " < ?", 1),
            new TokenPattern("Lt", " < ?", 1),
            new TokenPattern("LT", " >= ?", 1),
            new TokenPattern("StartingWith", " LIKE ? || '%'", 1, arg -> arg + "%"),
            new TokenPattern("EndingWith", " LIKE '%' || ?", 1, arg -> "%" + arg),
            new TokenPattern("Containing", " LIKE '%' || ? || '%'", 1, arg -> "%" + arg + "%"),
            new TokenPattern("Like", " LIKE ?", 1),
            new TokenPattern("NotLike", " NOT LIKE ?", 1),
            new TokenPattern("Regex", " ~ ?", 1),
            new TokenPattern("NotIn", " NOT IN (", -1, ")", true),
            new TokenPattern("Nin", " NOT IN (", -1, ")", true),
            new TokenPattern("In", " IN (", -1, ")", true),
            new TokenPattern("NotNull", " IS NOT NULL", 0),
            new TokenPattern("Null", " IS NULL", 0),
            new TokenPattern("NotEquals", " <> ?", 1),
            new TokenPattern("NotEqual", " <> ?", 1),
            new TokenPattern("Ne", " <> ?", 1),
            new TokenPattern("NE", " <> ?", 1),
            new TokenPattern("Not", " <> ?", 1),
            new TokenPattern("True", " = true", 0),
            new TokenPattern("False", " = false", 0),
            new TokenPattern("Exists", " EXISTS (?)", 1),
            new TokenPattern("Equals", " = ?", 1)
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
            throw new IllegalArgumentException("Invalid query method name: " + methodName +
                    ". Expected pattern: findBy..., countBy..., deleteBy..., existsBy...");
        }

        String operation = matcher.group(1);
        String topCount = matcher.group(2);
        String conditionsPart = matcher.group(4);

        OrderClause orderClause = parseOrderBy(conditionsPart);
        String conditionsWithoutOrder;
        if (orderClause != null) {
            int orderIndex = conditionsPart.indexOf("OrderBy");
            conditionsWithoutOrder = conditionsPart.substring(0, orderIndex);
        } else {
            conditionsWithoutOrder = conditionsPart;
        }

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
        for (String token : tokens) {
            if (token.equals("And")) {
                sql.append(" AND ");
                continue;
            } else if (token.equals("Or")) {
                sql.append(" OR ");
                continue;
            }

            PropertyCondition propCond = parsePropertyCondition(token);
            String columnName = mapper.getColumnName(propCond.property());
            TokenPattern operator = propCond.operator();

            if (operator.params() == 0) {
                sql.append(columnName).append(operator.sql());
            } else {
                sql.append(columnName).append(operator.sql());

                int paramsNeeded = operator.params();
                if (paramsNeeded == -1) {
                    handleVarargOperator(sql, bindings, args, paramIndex, columnName);
                    if (args[paramIndex] instanceof List) {
                        paramIndex++;
                    } else if (args[paramIndex].getClass().isArray()) {
                        paramIndex++;
                    }
                } else {
                    for (int j = 0; j < paramsNeeded; j++) {
                        Object value = args[paramIndex++];
                        if (operator.transformer() != null) {
                            value = operator.transformer().apply(value);
                        }
                        bindings.add(new ParameterBinding(paramIndex - 1, value));
                    }
                }
            }
        }

        return new WhereClause(sql.toString(), bindings);
    }

    private void handleVarargOperator(StringBuilder sql, List<ParameterBinding> bindings,
                                      Object[] args, int paramIndex, String columnName) {
        Object arg = args[paramIndex];
        boolean isEmpty = false;
        List<?> items = null;
        Object[] array = null;
        int size = 0;

        if (arg instanceof List<?> list) {
            items = list;
            size = list.size();
            isEmpty = list.isEmpty();
        } else if (arg.getClass().isArray()) {
            array = (Object[]) arg;
            size = array.length;
            isEmpty = array.length == 0;
        }

        if (isEmpty) {
            String currentSql = sql.toString();
            if (currentSql.endsWith(" IN (")) {
                int replaceStart = currentSql.lastIndexOf(columnName);
                sql.replace(replaceStart, sql.length(), "1=0");
            } else if (currentSql.endsWith(" NOT IN (")) {
                int replaceStart = currentSql.lastIndexOf(columnName);
                sql.replace(replaceStart, sql.length(), "1=1");
            }
        } else {
            String placeholders = String.join(", ",
                    java.util.Collections.nCopies(size, "?"));
            int insertPos = sql.lastIndexOf("(");
            sql.insert(insertPos + 1, placeholders);

            if (items != null) {
                for (Object item : items) {
                    bindings.add(new ParameterBinding(paramIndex++, item));
                }
            } else {
                assert array != null;
                for (Object item : array) {
                    bindings.add(new ParameterBinding(paramIndex++, item));
                }
            }
        }
    }

    private List<String> splitConditions(String conditions) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < conditions.length(); ) {
            if (conditions.startsWith("And", i) && i + 3 < conditions.length()
                    && (i + 3 == conditions.length() || Character.isUpperCase(conditions.charAt(i + 3)))) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
                tokens.add("And");
                i += 3;
            } else if (conditions.startsWith("Or", i) && i + 2 < conditions.length()
                    && (i + 2 == conditions.length() || Character.isUpperCase(conditions.charAt(i + 2)))) {
                if (!current.isEmpty()) {
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

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private PropertyCondition parsePropertyCondition(String token) {
        for (TokenPattern pattern : CONDITION_PATTERNS) {
            String suffix = pattern.name();
            if (token.endsWith(suffix)) {
                int suffixStart = token.length() - suffix.length();
                if (suffixStart == 0 ||
                        (suffixStart > 0 && Character.isUpperCase(token.charAt(suffixStart)))) {
                    String property = token.substring(0, suffixStart);
                    if (property.isEmpty()) {
                        return new PropertyCondition(token, new TokenPattern("Equals", " = ?", 1));
                    }
                    return new PropertyCondition(property, pattern);
                }
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
            case "count" -> QueryType.COUNT;
            case "delete" -> QueryType.DELETE;
            case "exists" -> QueryType.EXISTS;
            default -> QueryType.SELECT;
        };
    }
}