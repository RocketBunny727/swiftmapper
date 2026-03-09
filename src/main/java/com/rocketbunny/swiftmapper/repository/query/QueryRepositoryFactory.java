package com.rocketbunny.swiftmapper.repository.query;

import com.rocketbunny.swiftmapper.core.ConnectionManager;
import com.rocketbunny.swiftmapper.core.EntityMapper;
import com.rocketbunny.swiftmapper.query.model.ParameterBinding;
import com.rocketbunny.swiftmapper.query.model.ParsedQuery;
import com.rocketbunny.swiftmapper.query.QueryMethodParser;
import com.rocketbunny.swiftmapper.query.model.QueryType;
import com.rocketbunny.swiftmapper.repository.Repository;
import com.rocketbunny.swiftmapper.repository.SwiftRepositorySupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings({"unchecked", "rawtypes"})
public class QueryRepositoryFactory {

    public static <T, ID, R extends Repository<T, ID>> R createInterface(
            Class<R> repositoryInterface,
            Class<T> entityClass,
            ConnectionManager connectionManager) {

        return (R) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class[]{repositoryInterface},
                new QueryInvocationHandler(connectionManager, entityClass, repositoryInterface)
        );
    }

    @Deprecated
    public static <T, ID> Repository<T, ID> create(
            Class<T> entityClass,
            Class<ID> idClass,
            ConnectionManager connectionManager) {
        return new SwiftRepositorySupport<T, ID>(connectionManager, entityClass, idClass) {};
    }

    public static <T> Repository<T, ?> createAuto(
            Class<T> entityClass,
            ConnectionManager connectionManager) {
        Class<?> idClass = detectIdClass(entityClass);
        return new SwiftRepositorySupport<T, Object>(connectionManager, entityClass, (Class<Object>) idClass) {};
    }

    private static <T> Class<?> detectIdClass(Class<T> entityClass) {
        for (var field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(com.rocketbunny.swiftmapper.annotations.entity.Id.class)) {
                return field.getType();
            }
        }
        return String.class;
    }

    private static class QueryInvocationHandler implements InvocationHandler {
        private final ConnectionManager connectionManager;
        private final Class<?> entityClass;
        private final Class<?> idClass;
        private final EntityMapper<?> mapper;
        private final QueryMethodParser parser;
        private final SwiftRepositorySupport baseRepository;
        private final Map<String, MethodHandler> methodHandlers;

        public QueryInvocationHandler(ConnectionManager connectionManager, Class<?> entityClass,
                                      Class<?> repositoryInterface) {
            this.connectionManager = connectionManager;
            this.entityClass = entityClass;
            this.idClass = extractIdClass(repositoryInterface);

            this.mapper = EntityMapper.getInstance((Class) entityClass, connectionManager);
            this.parser = new QueryMethodParser(this.mapper);

            this.baseRepository = new SwiftRepositorySupport(connectionManager, entityClass, this.idClass) {};

            this.methodHandlers = new HashMap<>();

            registerStandardHandlers();
            registerInterfaceMethods(repositoryInterface);
        }

        private Class<?> extractIdClass(Class<?> repositoryInterface) {
            for (Type genericInterface : repositoryInterface.getGenericInterfaces()) {
                if (genericInterface instanceof ParameterizedType pt) {
                    if (pt.getRawType() == Repository.class) {
                        Type[] args = pt.getActualTypeArguments();
                        if (args.length > 1 && args[1] instanceof Class) {
                            return (Class<?>) args[1];
                        }
                    }
                }
            }
            return String.class;
        }

        private void registerStandardHandlers() {
            methodHandlers.put("save", args -> baseRepository.save(args[0]));
            methodHandlers.put("saveAll", args -> baseRepository.saveAll((List) args[0]));
            methodHandlers.put("update", args -> baseRepository.update(args[0]));
            methodHandlers.put("updateAll", args -> {
                if (args.length > 0 && args[0] instanceof List) {
                    return baseRepository.updateAll((List) args[0]);
                }
                return List.of();
            });
            methodHandlers.put("findById", args -> baseRepository.findById(args[0]));
            methodHandlers.put("existsById", args -> baseRepository.existsById(args[0]));
            methodHandlers.put("findAll", args -> baseRepository.findAll());
            methodHandlers.put("count", args -> baseRepository.count());
            methodHandlers.put("deleteById", args -> { baseRepository.deleteById(args[0]); return null; });
            methodHandlers.put("delete", args -> { baseRepository.delete(args[0]); return null; });
            methodHandlers.put("deleteAll", args -> {
                if (args.length > 0 && args[0] instanceof List) {
                    baseRepository.deleteAll((List) args[0]);
                } else {
                    baseRepository.deleteAll();
                }
                return null;
            });
            methodHandlers.put("deleteAllById", args -> {
                if (args.length > 0 && args[0] instanceof List) {
                    baseRepository.deleteAllById((List) args[0]);
                }
                return null;
            });
            methodHandlers.put("query", args -> {
                String sql = (String) args[0];
                Object[] params;
                if (args.length == 2) {
                    if (args[1] instanceof List) {
                        params = ((List<?>) args[1]).toArray();
                    } else if (args[1].getClass().isArray()) {
                        params = (Object[]) args[1];
                    } else {
                        params = new Object[]{args[1]};
                    }
                } else if (args.length > 2) {
                    params = new Object[args.length - 1];
                    System.arraycopy(args, 1, params, 0, args.length - 1);
                } else {
                    params = new Object[0];
                }
                return baseRepository.query(sql, params);
            });
            methodHandlers.put("criteria", args -> baseRepository.criteria());
            methodHandlers.put("sql", args -> baseRepository.sql());
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
                    || name.startsWith("delete") || name.startsWith("exists")
                    || name.startsWith("query");
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

                    Class<?> returnType = method.getReturnType();

                    if (parsed.singleResult() || returnType == Optional.class) {
                        if (results.isEmpty()) {
                            return returnType == Optional.class ? Optional.empty() : null;
                        }
                        Object single = results.get(0);
                        return returnType == Optional.class ? Optional.of(single) : single;
                    }

                    if (returnType == List.class) {
                        return results;
                    }

                    return results.isEmpty() ? null : results.get(0);

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