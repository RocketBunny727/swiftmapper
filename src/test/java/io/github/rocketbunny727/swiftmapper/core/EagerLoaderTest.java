package io.github.rocketbunny727.swiftmapper.core;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Column;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Entity;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Id;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Table;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.FetchType;
import io.github.rocketbunny727.swiftmapper.annotations.relationship.OneToMany;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EagerLoaderTest {

    @Test
    void batchLoadsOneToManyRelationWithSingleSelectForAllParents() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:eager_loader;DB_CLOSE_DELAY=-1")) {
            createSchema(connection);

            Parent first = new Parent(1L);
            Parent second = new Parent(2L);
            CountingConnection countingConnection = CountingConnection.wrap(connection);

            EagerLoader.batchLoad(List.of(first, second), Parent.class,
                    countingConnection.connection(), null, "children");

            assertEquals(1, countingConnection.prepareStatementCount());
            assertEquals(
                    "SELECT * FROM \"eager_loader_children\" WHERE \"parent_id\" IN (?,?)",
                    countingConnection.preparedSql().get(0)
            );

            assertEquals(2, first.children.size());
            assertEquals("first child", first.children.get(0).name);
            assertEquals("second child", first.children.get(1).name);

            assertEquals(1, second.children.size());
            assertEquals("third child", second.children.get(0).name);
        }
    }

    private static void createSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS \"eager_loader_children\"");
            statement.execute("DROP TABLE IF EXISTS \"eager_loader_parents\"");
            statement.execute("""
                    CREATE TABLE "eager_loader_parents" (
                        "id" BIGINT PRIMARY KEY,
                        "name" VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE "eager_loader_children" (
                        "id" BIGINT PRIMARY KEY,
                        "parent_id" BIGINT,
                        "name" VARCHAR(255)
                    )
                    """);
            statement.execute("INSERT INTO \"eager_loader_parents\" (\"id\", \"name\") VALUES (1, 'first parent'), (2, 'second parent')");
            statement.execute("""
                    INSERT INTO "eager_loader_children" ("id", "parent_id", "name") VALUES
                    (10, 1, 'first child'),
                    (11, 1, 'second child'),
                    (12, 2, 'third child')
                    """);
        }
    }

    private record CountingConnection(Connection connection, List<String> preparedSql) {
        static CountingConnection wrap(Connection delegate) {
            List<String> preparedSql = new ArrayList<>();
            InvocationHandler handler = (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())
                        && args != null
                        && args.length > 0
                        && args[0] instanceof String sql) {
                    preparedSql.add(sql);
                }
                return method.invoke(delegate, args);
            };
            Connection proxy = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    handler
            );
            return new CountingConnection(proxy, preparedSql);
        }

        int prepareStatementCount() {
            return preparedSql.size();
        }
    }

    @Entity
    @Table(name = "eager_loader_parents")
    public static class Parent {
        @Id
        Long id;

        @Column
        String name;

        @OneToMany(mappedBy = "parent", fetch = FetchType.EAGER)
        List<Child> children;

        public Parent() {
        }

        Parent(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "eager_loader_children")
    public static class Child {
        @Id
        Long id;

        @Column
        String name;

        public Child() {
        }
    }
}
