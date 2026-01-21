package ru.nsu.swiftmapper.repository.query;

import ru.nsu.swiftmapper.core.EntityMapper;
import ru.nsu.swiftmapper.core.Session;
import ru.nsu.swiftmapper.query.QueryMethodParser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@SuppressWarnings({"unchecked", "rawtypes"})
public class QueryRepositoryFactory {
    public static <T, ID> QueryRepository<T, ID> create(Class<T> entityClass, Connection connection) {
        return (QueryRepository<T, ID>) Proxy.newProxyInstance(
                entityClass.getClassLoader(),
                new Class[]{QueryRepository.class},
                new QueryInvocationHandler(connection, entityClass)
        );
    }

    private static class QueryInvocationHandler implements InvocationHandler {
        private final Connection connection;
        private final Class<?> entityClass;
        private final EntityMapper<?> mapper;
        private final QueryMethodParser parser;

        public QueryInvocationHandler(Connection connection, Class<?> entityClass) {
            this.connection = connection;
            this.entityClass = entityClass;
            this.mapper = new EntityMapper<>(entityClass);
            this.parser = new QueryMethodParser(mapper);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            return switch (methodName) {
                case "save" -> handleSave(args);
                case "findById" -> handleFindById(args);
                case "update" -> {
                    handleUpdate(args);
                    yield null;
                }
                case "delete" -> {
                    handleDelete(args);
                    yield null;
                }
                case "findAll" -> handleFindAll();
                case "query" -> handleQuery(args);
                default -> {
                    if (methodName.startsWith("findBy")) {
                        yield handleDynamicQuery(method, args);
                    }
                    throw new UnsupportedOperationException("Method not supported: " + methodName);
                }
            };
        }

        private Object handleDynamicQuery(Method method, Object[] args) throws Throwable {
            String sql = parser.parse(method, args);
            List result = executeQuery(sql, args != null ? args : new Object[0]);
            Class<?> returnType = method.getReturnType();
            if (returnType == Optional.class) {
                return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
            }
            return result;
        }

        private List handleQuery(Object[] args) throws Throwable {
            String sql = (String) args[0];
            Object[] params = Arrays.copyOfRange(args, 1, args.length);
            return executeQuery(sql, params);
        }

        private List handleFindAll() throws Throwable {
            String sql = "SELECT * FROM " + mapper.getTableName();
            return executeQuery(sql);
        }

        private Object handleFindById(Object[] args) throws Throwable {
            String sql = "SELECT * FROM " + mapper.getTableName() + " WHERE " + mapper.getIdColumn() + " = ?";
            List result = executeQuery(sql, args[0]);
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
        }

        private Object handleSave(Object[] args) throws Throwable {
            Session session = new Session(connection, entityClass);
            return session.save(args[0]);
        }

        private void handleUpdate(Object[] args) throws Throwable {
            Session session = new Session(connection, entityClass);
            session.update(args[0]);
        }

        private void handleDelete(Object[] args) throws Throwable {
            Session session = new Session(connection, entityClass);
            session.delete(args[0]);
        }

        private List executeQuery(String sql, Object... params) throws SQLException {
            List result = new ArrayList();
            return getList(sql, result, connection, mapper, params);
        }

        public static List getList(String sql, List result, Connection connection, EntityMapper<?> mapper, Object[] params) throws SQLException {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        stmt.setObject(i + 1, params[i]);
                    }
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapper.map(rs));
                    }
                }
            }
            return result;
        }
    }
}
