package io.github.rocketbunny727.swiftmapper.query;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Column;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Entity;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Id;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Table;
import io.github.rocketbunny727.swiftmapper.core.EntityMapper;
import io.github.rocketbunny727.swiftmapper.dialect.SqlDialect;
import io.github.rocketbunny727.swiftmapper.query.model.ParsedQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryMethodParserDialectTest {

    @Test
    void appliesDialectLimitForSingleResultDerivedQuery() throws NoSuchMethodException {
        EntityMapper<QueryUser> mapper = EntityMapper.getInstance(QueryUser.class, null);
        QueryMethodParser parser = new QueryMethodParser(mapper, SqlDialect.SQLSERVER);
        Method method = QueryRepository.class.getDeclaredMethod("findFirstByEmail", String.class);

        ParsedQuery query = parser.parse(method, new Object[]{"test@example.com"});

        assertEquals(
                "SELECT t0.* FROM [query_users] t0 WHERE t0.[email] = ? ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY",
                query.sql()
        );
        assertEquals("test@example.com", query.bindings().get(0).value());
    }

    interface QueryRepository {
        QueryUser findFirstByEmail(String email);
    }

    @Entity
    @Table(name = "query_users")
    static class QueryUser {
        @Id
        private Long id;

        @Column
        private String email;
    }
}
