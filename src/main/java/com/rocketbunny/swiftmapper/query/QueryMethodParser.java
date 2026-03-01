package com.rocketbunny.swiftmapper.query;

import com.rocketbunny.swiftmapper.annotations.entity.Column;
import com.rocketbunny.swiftmapper.annotations.entity.Id;
import com.rocketbunny.swiftmapper.annotations.entity.Table;
import com.rocketbunny.swiftmapper.annotations.relationship.JoinColumn;
import com.rocketbunny.swiftmapper.core.EntityMapper;
import com.rocketbunny.swiftmapper.query.model.*;
import com.rocketbunny.swiftmapper.utils.logger.SwiftLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryMethodParser {
    private static final SwiftLogger log = SwiftLogger.getLogger(QueryMethodParser.class);
    private final EntityMapper<?> mapper;

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(find|findAll|findFirst|findTop|count|delete|exists)(\\d*)(By)(.*)$"
    );

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
            "TABLE", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "UNION",
            "EXEC", "EXECUTE", "SCRIPT", "--", "/*", "*/", ";"
    );

    private static final List<TokenPattern> CONDITION_PATTERNS = List.of(
            new TokenPattern("Between", " BETWEEN ? AND ?", 2),
            new TokenPattern("GreaterThanEquals", " >= ?", 1),
            new TokenPattern("GreaterThanEqual", " >= ?", 1),
            new TokenPattern("Gte", " >= ?", 1),
            new TokenPattern("GreaterThan", " > ?", 1),
            new TokenPattern("Gt", " > ?", 1),
            new TokenPattern("LessThanEquals", " <= ?", 1),
            new TokenPattern("LessThanEqual", " <= ?", 1),
            new TokenPattern("Lte", " <= ?", 1),
            new TokenPattern("LessThan", " < ?", 1),
            new TokenPattern("Lt", " < ?", 1),
            new TokenPattern("StartingWith", " LIKE ?", 1, arg -> escapeLikePattern(arg) + "%"),
            new TokenPattern("EndingWith", " LIKE ?", 1, arg -> "%" + escapeLikePattern(arg)),
            new TokenPattern("Containing", " LIKE ?", 1, arg -> "%" + escapeLikePattern(arg) + "%"),
            new TokenPattern("Like", " LIKE ?", 1, QueryMethodParser::escapeLikePattern),
            new TokenPattern("NotLike", " NOT LIKE ?", 1, QueryMethodParser::escapeLikePattern),
            new TokenPattern("Regex", " ~ ?", 1),
            new TokenPattern("NotIn", " NOT IN ", -1),
            new TokenPattern("Nin", " NOT IN ", -1),
            new TokenPattern("In", " IN ", -1),
            new TokenPattern("NotNull", " IS NOT NULL", 0),
            new TokenPattern("Null", " IS NULL", 0),
            new TokenPattern("NotEquals", " <> ?", 1),
            new TokenPattern("Ne", " <> ?", 1),
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

    private static String escapeLikePattern(Object arg) {
        if (arg == null) return null;
        String str = arg.toString();
        return str.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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
        String conditionsWithoutOrder;
        if (orderClause != null) {
            int orderIndex = conditionsPart.indexOf("OrderBy");
            conditionsWithoutOrder = conditionsPart.substring(0, orderIndex);
        } else {
            conditionsWithoutOrder = conditionsPart;
        }

        StringBuilder sql = new StringBuilder();
        List<ParameterBinding> bindings = new ArrayList<>();
        List<JoinInfo> joins = new ArrayList<>();

        QueryType queryType = determineQueryType(operation);

        WhereClause whereClause = null;
        if (!conditionsWithoutOrder.isEmpty()) {
            whereClause = parseWhereClause(conditionsWithoutOrder, args, joins);
        }

        sql.append(buildSelectClause(queryType));
        sql.append(" FROM ").append(escapeIdentifier(mapper.getTableName())).append(" t0");

        for (int i = 0; i < joins.size(); i++) {
            JoinInfo join = joins.get(i);
            sql.append(" LEFT JOIN ").append(escapeIdentifier(join.tableName())).append(" t").append(i + 1)
                    .append(" ON t0.").append(escapeIdentifier(join.foreignKey())).append(" = t")
                    .append(i + 1).append(".").append(escapeIdentifier(join.primaryKey()));
        }

        if (whereClause != null) {
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

    private String escapeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (!identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new SecurityException("Invalid SQL identifier: " + identifier);
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private WhereClause parseWhereClause(String conditions, Object[] args, List<JoinInfo> joins) {
        StringBuilder sql = new StringBuilder();
        List<ParameterBinding> bindings = new ArrayList<>();

        List<String> tokens = splitConditions(conditions);

        int argIndex = 0;
        boolean expectOperand = true;

        for (String token : tokens) {
            if (token.equals("And")) {
                sql.append(" AND ");
                expectOperand = true;
                continue;
            } else if (token.equals("Or")) {
                sql.append(" OR ");
                expectOperand = true;
                continue;
            }

            if (!expectOperand) {
                sql.append(" AND ");
            }

            NestedField nested = parseNestedField(token);
            String columnName;
            PropertyCondition propCond;

            if (nested != null) {
                String alias = "t" + (joins.size() + 1);
                String tableName = getTableName(nested.entityClass());

                JoinInfo existingJoin = findExistingJoin(joins, nested.foreignKey());
                if (existingJoin == null) {
                    joins.add(new JoinInfo(tableName, nested.foreignKey(), nested.primaryKey()));
                    alias = "t" + joins.size();
                } else {
                    alias = "t" + (joins.indexOf(existingJoin) + 1);
                }

                propCond = parsePropertyCondition(nested.propertyCondition());
                columnName = alias + "." + escapeIdentifier(getActualColumnName(nested.entityClass(), nested.propertyName()));
            } else {
                propCond = parsePropertyCondition(token);
                columnName = "t0." + escapeIdentifier(getActualColumnName(mapper.getEntityClass(), propCond.property()));
            }

            int consumedArgs = buildCondition(sql, bindings, args, columnName, propCond.operator(), argIndex);
            argIndex += consumedArgs;

            expectOperand = false;
        }

        return new WhereClause(sql.toString(), bindings);
    }

    private String getActualColumnName(Class<?> entityClass, String propertyName) {
        validatePropertyName(propertyName);

        String searchName = propertyName.toLowerCase();

        List<Field> allFields = getAllFields(entityClass);
        for (Field field : allFields) {
            String fieldName = field.getName();
            String normalizedFieldName = fieldName.toLowerCase().replace("_", "");
            String normalizedSearchName = searchName.replace("_", "");

            if (normalizedFieldName.equals(normalizedSearchName) || fieldName.equalsIgnoreCase(propertyName)) {
                if (field.isAnnotationPresent(Column.class)) {
                    String colName = field.getAnnotation(Column.class).name();
                    if (colName != null && !colName.isEmpty()) {
                        validateColumnName(colName);
                        return colName;
                    }
                }
                return fieldName;
            }
        }

        throw new IllegalArgumentException("Property not found: " + propertyName + " in " + entityClass.getName());
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    private void validatePropertyName(String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) {
            throw new IllegalArgumentException("Property name cannot be null or empty");
        }
        if (propertyName.length() > 64) {
            throw new IllegalArgumentException("Property name too long: " + propertyName);
        }
        String upper = propertyName.toUpperCase();
        for (String keyword : SQL_KEYWORDS) {
            if (upper.contains(keyword)) {
                throw new SecurityException("SQL injection attempt detected in property: " + propertyName);
            }
        }
        if (!propertyName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid property name format: " + propertyName);
        }
    }

    private void validateColumnName(String columnName) {
        if (columnName == null || columnName.isEmpty()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        if (columnName.length() > 64) {
            throw new IllegalArgumentException("Column name too long: " + columnName);
        }

        if (!columnName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new SecurityException("Invalid column name format (SQL injection prevention): " + columnName);
        }

        if (SQL_KEYWORDS.contains(columnName.toUpperCase())) {
            throw new SecurityException("Column name cannot be a reserved SQL keyword: " + columnName);
        }
    }

    private JoinInfo findExistingJoin(List<JoinInfo> joins, String foreignKey) {
        for (JoinInfo join : joins) {
            if (join.foreignKey().equals(foreignKey)) {
                return join;
            }
        }
        return null;
    }

    private String getTableName(Class<?> entityClass) {
        var table = entityClass.getAnnotation(Table.class);
        String tableName;
        if (table != null && !table.name().isEmpty()) {
            tableName = table.name();
        } else {
            tableName = entityClass.getSimpleName().toLowerCase();
        }
        validateTableName(tableName);
        return tableName;
    }

    private void validateTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (tableName.length() > 64) {
            throw new IllegalArgumentException("Table name too long: " + tableName);
        }

        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new SecurityException("Invalid table name format (SQL injection prevention): " + tableName);
        }

        if (SQL_KEYWORDS.contains(tableName.toUpperCase())) {
            throw new SecurityException("Table name cannot be a reserved SQL keyword: " + tableName);
        }
    }

    private int buildCondition(StringBuilder sql, List<ParameterBinding> bindings,
                               Object[] args, String columnName, TokenPattern operator, int argIndex) {
        if (operator.params() == 0) {
            sql.append(columnName).append(operator.sql());
            return 0;
        }

        if (operator.params() == -1) {
            if (args == null || argIndex >= args.length) return 0;
            Object arg = args[argIndex];

            List<Object> items = new ArrayList<>();
            if (arg instanceof Collection<?> c) {
                items.addAll(c);
            } else if (arg != null && arg.getClass().isArray()) {
                items.addAll(Arrays.asList((Object[]) arg));
            }

            if (items.isEmpty()) {
                sql.append(operator.name().contains("Not") ? "1=1" : "1=0");
            } else {
                sql.append(columnName).append(operator.sql()).append("(");
                String placeholders = String.join(", ", Collections.nCopies(items.size(), "?"));
                sql.append(placeholders).append(")");

                for (Object item : items) {
                    bindings.add(new ParameterBinding(bindings.size(), item));
                }
            }
            return 1;
        }

        sql.append(columnName).append(operator.sql());
        int paramsNeeded = operator.params();

        for (int j = 0; j < paramsNeeded; j++) {
            if (argIndex + j >= args.length) break;
            Object value = args[argIndex + j];
            if (operator.transformer() != null) {
                value = operator.transformer().apply(value);
            }
            bindings.add(new ParameterBinding(bindings.size(), value));
        }
        return paramsNeeded;
    }

    private NestedField parseNestedField(String token) {
        for (Map.Entry<String, com.rocketbunny.swiftmapper.annotations.relationship.RelationshipField> entry : mapper.getRelationshipFields().entrySet()) {
            String fieldName = entry.getKey();
            var relField = entry.getValue();

            String capitalizedField = capitalize(fieldName);
            if (token.startsWith(capitalizedField)) {
                String remainder = token.substring(capitalizedField.length());
                if (remainder.isEmpty()) continue;

                Class<?> targetClass = getTargetClass(relField.field());
                String propertyCondition = remainder;

                String fkColumn = fieldName.toLowerCase() + "_id";
                var joinColumn = relField.field().getAnnotation(JoinColumn.class);
                if (joinColumn != null && !joinColumn.name().isEmpty()) {
                    fkColumn = joinColumn.name();
                    validateColumnName(fkColumn);
                }

                String pkColumn = "id";
                try {
                    List<Field> fields = getAllFields(targetClass);
                    for (Field f : fields) {
                        if (f.isAnnotationPresent(Id.class)) {
                            pkColumn = f.getName();
                            if (f.isAnnotationPresent(Column.class)) {
                                String colName = f.getAnnotation(Column.class).name();
                                if (colName != null && !colName.isEmpty()) {
                                    validateColumnName(colName);
                                    pkColumn = colName;
                                }
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not determine PK column: {}", e.getMessage());
                }

                return new NestedField(targetClass, fkColumn, pkColumn,
                        propertyCondition, extractPropertyName(propertyCondition));
            }
        }
        return null;
    }

    private String extractPropertyName(String condition) {
        for (TokenPattern pattern : CONDITION_PATTERNS) {
            if (condition.endsWith(pattern.name())) {
                return condition.substring(0, condition.length() - pattern.name().length());
            }
        }
        return condition;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private Class<?> getTargetClass(Field field) {
        Class<?> type = field.getType();
        if (Collection.class.isAssignableFrom(type)) {
            ParameterizedType pt = (ParameterizedType) field.getGenericType();
            return (Class<?>) pt.getActualTypeArguments()[0];
        }
        return type;
    }

    private List<String> splitConditions(String conditions) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < conditions.length(); ) {
            if (conditions.startsWith("And", i) && i + 3 <= conditions.length()
                    && (i + 3 == conditions.length() || Character.isUpperCase(conditions.charAt(i + 3)))) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
                tokens.add("And");
                i += 3;
            } else if (conditions.startsWith("Or", i) && i + 2 <= conditions.length()
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
                    validatePropertyName(property);
                    return new PropertyCondition(property, pattern);
                }
            }
        }

        validatePropertyName(token);
        return new PropertyCondition(token, new TokenPattern("Equals", " = ?", 1));
    }

    private OrderClause parseOrderBy(String conditions) {
        Matcher matcher = ORDER_PATTERN.matcher(conditions);
        if (!matcher.find()) {
            return null;
        }

        String property = matcher.group(1);
        String directionStr = matcher.group(2);

        validatePropertyName(property);

        SortDirection direction = SortDirection.ASC;
        if (directionStr != null) {
            direction = SortDirection.valueOf(directionStr.toUpperCase());
        }

        String column = getActualColumnName(mapper.getEntityClass(), property);

        return new OrderClause("t0." + escapeIdentifier(column) + " " + direction.name());
    }

    private String buildSelectClause(QueryType type) {
        return switch (type) {
            case SELECT -> "SELECT t0.*";
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

    private enum SortDirection {
        ASC, DESC
    }
}