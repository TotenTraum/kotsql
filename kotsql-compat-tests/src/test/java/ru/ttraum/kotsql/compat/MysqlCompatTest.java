package ru.ttraum.kotsql.compat;

import ru.ttraum.kotsql.jdbc.KotsqlJdbcDriver;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CompatApp.class, properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.jpa.properties.hibernate.jdbc.batch_size=50"})
class MysqlCompatTest extends AbstractCompatTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Databases::mysqlUrl);
        r.add("spring.datasource.username", () -> "app");
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.datasource.driver-class-name", KotsqlJdbcDriver.class::getName);
    }

    /** Hibernate maps these to DATETIME, holding UTC by default; the session's zone doesn't touch that type. */
    @Override String utcText(String column) { return "cast(" + column + " as char(19))"; }

    @Override Map<String, Object> validationProperties() {
        return Map.of("spring.datasource.url", Databases.mysqlUrl(), "spring.datasource.username", "app", "spring.datasource.password", "app",
            "spring.datasource.driver-class-name", KotsqlJdbcDriver.class.getName(), "spring.jpa.hibernate.ddl-auto", "validate");
    }
}
