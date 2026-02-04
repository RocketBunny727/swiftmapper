package ru.nsu.swiftmapper.repository.query;

import ru.nsu.swiftmapper.core.EntityMapper;
import ru.nsu.swiftmapper.core.Session;
import ru.nsu.swiftmapper.query.QueryMethodParser;
import ru.nsu.swiftmapper.repository.Repository;
import ru.nsu.swiftmapper.query.QueryMethodParser.QueryType;
import ru.nsu.swiftmapper.query.QueryMethodParser.ParsedQuery;
import ru.nsu.swiftmapper.query.QueryMethodParser.ParameterBinding;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class QueryRepositoryFactory {

    public static <T, ID, R extends Repository<T, ID>> R create(
            Class<R> repositoryInterface,
            Class<T> entityClass,
            Connection connection) {

        return (R) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class[]{repositoryInterface},
                new QueryInvocationHandler(connection, entityClass, repositoryInterface)
        );
    }

    private static class QueryInvocationHandler implements InvocationHandler {
        private final Connection connection;
        private final Class<?> entityClass;
        private final EntityMapper<?> mapper;
        private final QueryMethodParser parser;
        private final Map<String, MethodHandler> methodHandlers;

        public QueryInvocationHandler(Connection connection, Class<?> entityClass,
                                      Class<?> repositoryInterface) {
            this.connection = connection;
            this.entityClass = entityClass;
            this.mapper = new EntityMapper<>(entityClass);
            this.parser = new QueryMethodParser(mapper);
            this.methodHandlers = new HashMap<>();

            registerStandardHandlers();
            registerInterfaceMethods(repositoryInterface);
        }

        private void registerStandardHandlers() {
            methodHandlers.put("save", this::handleSave);
            methodHandlers.put("findById", this::handleFindById);
            methodHandlers.put("update", (args) -> { handleUpdate(args); return null; });
            methodHandlers.put("delete", (args) -> { handleDelete(args); return null; });
            methodHandlers.put("findAll", this::handleFindAll);
            methodHandlers.put("query", this::handleQuery);
        }

        private void registerInterfaceMethods(Class<?> interfaceClass) {
            for (Method method : interfaceClass.getDeclaredMethods()) {
                String name = method.getName();
                if (methodHandlers.containsKey(name)) continue;

                if (isQueryMethod(name)) {
                    methodHandlers.put(name, (args) -> handleDerivedQuery(method, args));
                }
            }
        }

        private boolean isQueryMethod(String name) {
            return name.startsWith("find") || name.startsWith("count")
                    || name.startsWith("delete") || name.startsWith("exists");
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }

            MethodHandler handler = methodHandlers.get(methodName);
            if (handler == null) {
                throw new UnsupportedOperationException(
                        "Method not supported: " + methodName +
                                ". Supported patterns: findBy..., countBy..., deleteBy..., existsBy..."
                );
            }

            Object result = handler.handle(args);

            return convertResult(result, method);
        }

        private Object handleDerivedQuery(Method method, Object[] args) throws Exception {
            ParsedQuery parsed = parser.parse(method, args);

            try (PreparedStatement stmt = connection.prepareStatement(parsed.sql())) {
                int paramIndex = 1;
                for (ParameterBinding binding : parsed.bindings()) {
                    stmt.setObject(paramIndex++, binding.value());
                }

                QueryType queryType = parsed.queryType();

                if (queryType == QueryType.SELECT) {
                    List results = new ArrayList();
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            results.add(mapper.map(rs));
                        }
                    }

                    if (parsed.singleResult() ||
                            method.getReturnType() == Optional.class) {
                        if (results.isEmpty()) return Optional.empty();
                        Object single = results.get(0);
                        return method.getReturnType() == Optional.class
                                ? Optional.of(single) : single;
                    }

                    return results;
                } else if (queryType == QueryType.COUNT) {
                    try (ResultSet rs = stmt.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                } else if (queryType == QueryType.EXISTS) {
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next();
                    }
                } else if (queryType == QueryType.DELETE) {
                    return stmt.executeUpdate();
                }
            }
            return null;
        }

        private Object convertResult(Object result, Method method) {
            Class<?> returnType = method.getReturnType();

            if (result == null || returnType.isInstance(result)) {
                return result;
            }

            if (result instanceof List list) {
                if (returnType == List.class) {
                    return result;
                }
                if (!list.isEmpty()) {
                    return list.get(0);
                }
                return null;
            }

            return result;
        }

        private Object handleSave(Object[] args) throws Exception {
            Session session = new Session(connection, entityClass);
            return session.save(args[0]);
        }

        private Object handleFindById(Object[] args) throws Exception {
            String sql = "SELECT * FROM " + mapper.getTableName()
                    + " WHERE " + mapper.getIdColumn() + " = ?";
            List result = executeQuery(sql, args[0]);
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
        }

        private void handleUpdate(Object[] args) throws Exception {
            Session session = new Session(connection, entityClass);
            session.update(args[0]);
        }

        private void handleDelete(Object[] args) throws Exception {
            Session session = new Session(connection, entityClass);
            session.delete(args[0]);
        }

        private Object handleFindAll(Object[] args) throws Exception {
            String sql = "SELECT * FROM " + mapper.getTableName();
            return executeQuery(sql);
        }

        private Object handleQuery(Object[] args) throws Exception {
            String sql = (String) args[0];
            Object[] params = Arrays.copyOfRange(args, 1, args.length);
            return executeQuery(sql, params);
        }

        private List executeQuery(String sql, Object... params) throws SQLException {
            List result = new ArrayList();
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapper.map(rs));
                    }
                }
            }
            return result;
        }

        @FunctionalInterface
        private interface MethodHandler {
            Object handle(Object[] args) throws Exception;
        }
    }
}