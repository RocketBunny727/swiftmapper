package io.github.rocketbunny727.swiftmapper.criteria;

import io.github.rocketbunny727.swiftmapper.criteria.model.BuiltQuery;
import io.github.rocketbunny727.swiftmapper.dialect.SqlDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SQLQueryBuilderTest {

    @Test
    void buildsDefaultLimitAndOffsetQuery() {
        BuiltQuery query = new SQLQueryBuilder(SqlDialect.POSTGRESQL)
                .selectAll()
                .from("users")
                .where("email", "test@example.com")
                .orderBy("id")
                .limit(5)
                .offset(10)
                .build();

        assertEquals("SELECT * FROM \"users\" t WHERE \"email\" = ? ORDER BY \"id\" ASC LIMIT 5 OFFSET 10", query.getSql());
        assertEquals(1, query.getParams().size());
        assertEquals("test@example.com", query.getParams().get(0));
    }

    @Test
    void buildsSqlServerPagingQueryWithRequiredOrderBy() {
        BuiltQuery query = new SQLQueryBuilder(SqlDialect.SQLSERVER)
                .selectAll()
                .from("users")
                .limit(5)
                .offset(10)
                .build();

        assertEquals(
                "SELECT * FROM [users] t ORDER BY (SELECT NULL) OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY",
                query.getSql()
        );
    }

    @Test
    void buildsForUpdateNoWaitOnce() {
        BuiltQuery query = new SQLQueryBuilder(SqlDialect.POSTGRESQL)
                .selectAll()
                .from("users")
                .where("id", 1L)
                .forUpdateNoWait()
                .build();

        assertEquals("SELECT * FROM \"users\" t WHERE \"id\" = ? FOR UPDATE NOWAIT", query.getSql());
    }
}
