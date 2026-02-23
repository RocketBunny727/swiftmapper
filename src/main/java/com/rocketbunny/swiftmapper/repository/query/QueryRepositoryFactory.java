package com.rocketbunny.swiftmapper.repository.query;

import com.rocketbunny.swiftmapper.core.ConnectionManager;
import com.rocketbunny.swiftmapper.core.EntityMapper;
import com.rocketbunny.swiftmapper.core.Session;
import com.rocketbunny.swiftmapper.query.model.ParameterBinding;
import com.rocketbunny.swiftmapper.query.model.ParsedQuery;
import com.rocketbunny.swiftmapper.query.QueryMethodParser;
import com.rocketbunny.swiftmapper.query.model.QueryType;
import com.rocketbunny.swiftmapper.repository.Repository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class QueryRepositoryFactory {

    public static <T, ID, R extends Repository<T, ID>> R create(
            Class<R> repositoryInterface,
            Class<T> entityClass,
            ConnectionManager connectionManager) {

        return (R) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class[]{repositoryInterface},
                new QueryInvocationHandler(connectionManager, entityClass, repositoryInterface)
        );
    }

    private static class QueryInvocationHandler implements InvocationHandler {
        private final ConnectionManager connectionManager;
        private final Class<?> entityClass;
        private final EntityMapper<?> mapper;
        private final QueryMethodParser parser;
        private final Map<String, MethodHandler> methodHandlers;

        public QueryInvocationHandler(ConnectionManager connectionManager, Class<?> entityClass,
                                      Class<?> repositoryInterface) {
            this.connectionManager = connectionManager;
            this.entityClass = entityClass;

            this.mapper = EntityMapper.getInstance((Class) entityClass, connectionManager);
            this.parser = new QueryMethodParser(this.mapper);
            this.methodHandlers = new HashMap<>();

            registerStandardHandlers();
            registerInterfaceMethods(repositoryInterface);
        }

        private void registerStandardHandlers() {
            methodHandlers.put("save", args -> new Session(connectionManager, entityClass).save(args[0]));
            methodHandlers.put("findById", args -> new Session(connectionManager, entityClass).findById(args[0]));
            methodHandlers.put("update", args -> {
                new Session(connectionManager, entityClass).update(args[0]);
                return null;
            });
            methodHandlers.put("delete", args -> {
                new Session(connectionManager, entityClass).delete(args[0]);
                return null;
            });
            methodHandlers.put("findAll", args -> new Session(connectionManager, entityClass).findAll());
            methodHandlers.put("query", args -> {
                String sql = (String) args[0];
                Object[] params = Arrays.copyOfRange(args, 1, args.length);
                return new Session(connectionManager, entityClass).query(sql, params);
            });
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

            try (Connection connection = connectionManager.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(parsed.sql())) {

                int paramIndex = 1;
                for (ParameterBinding binding : parsed.bindings()) {
                    stmt.setObject(paramIndex++, binding.value());
                }

                QueryType queryType = parsed.queryType();

                if (queryType == QueryType.SELECT) {
                    List<Object> results = new ArrayList<>();
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            results.add(mapper.map(rs));
                        }
                    }

                    // Корректная обработка Optional и предотвращение ClassCastException
                    if (parsed.singleResult() || method.getReturnType() == Optional.class) {
                        if (results.isEmpty()) {
                            return method.getReturnType() == Optional.class ? Optional.empty() : null;
                        }
                        Object single = results.get(0);
                        return method.getReturnType() == Optional.class ? Optional.of(single) : single;
                    }

                    return results;
                } else if (queryType == QueryType.COUNT) {
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) return rs.getLong(1);
                    }
                    return 0L;
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

        @FunctionalInterface
        private interface MethodHandler {
            Object handle(Object[] args) throws Exception;
        }
    }
}