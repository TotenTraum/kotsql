package dev.ksql.compatreactive;

import dev.ksql.compat.Databases;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = ReactiveApp.class)
class PostgresReactiveTest extends AbstractReactiveTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> Databases.postgresUrl().replace("jdbc:", "r2dbc:"));
        r.add("spring.r2dbc.username", () -> "app");
        r.add("spring.r2dbc.password", () -> "app");
    }
    @Override String idColumn() { return "BIGSERIAL PRIMARY KEY"; }
}
