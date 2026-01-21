package ru.nsu.swiftmapper.query;

import ru.nsu.swiftmapper.core.EntityMapper;
import ru.nsu.swiftmapper.logger.SwiftLogger;

import java.lang.reflect.Method;

public class QueryMethodParser {
    private static final SwiftLogger log = SwiftLogger.getLogger(QueryMethodParser.class);
    private final EntityMapper<?> mapper;

    public QueryMethodParser(EntityMapper<?> mapper) {
        this.mapper = mapper;
    }

    public String parse(Method method, Object[] args) {
        String methodName = method.getName();
        log.debug("Parsing query method: {}", methodName);

        if (methodName.equals("findAll")) {
            return "SELECT * FROM " + mapper.getTableName();
        }

        String criteria = methodName.substring(6);
        String[] parts = criteria.split("(?<=And)(?=[A-Z])");

        StringBuilder where = new StringBuilder(" WHERE ");
        for (int i = 0; i < parts.length; i++) {
            String fieldName = parts[i].startsWith("And") ? parts[i].substring(3) : parts[i];
            String columnName = mapper.getColumnName(fieldName);
            where.append(columnName).append(" = ?");

            if (i < parts.length - 1) {
                where.append(" AND ");
            }
        }

        String sql = "SELECT * FROM " + mapper.getTableName() + where;
        log.debug("Generated SQL: {}", sql);
        return sql.toString();
    }
}
