package dev.ksql.compat;

import dev.ksql.jdbc.KsqlJdbcDriver;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = {CompatApp.class, dev.ksql.compatpg.PostgresEntities.class}, properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.jpa.properties.hibernate.jdbc.batch_size=50"})
class PostgresCompatTest extends AbstractCompatTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Databases::postgresUrl);
        r.add("spring.datasource.username", () -> "app");
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.datasource.driver-class-name", KsqlJdbcDriver.class::getName);
    }

    @org.junit.jupiter.api.Test void sequencesAndArrays() {
        var manager = entities.getEntityManagerFactory().createEntityManager();
        try {
            manager.getTransaction().begin();
            dev.ksql.compatpg.Tagged first = new dev.ksql.compatpg.Tagged();
            first.tags = new String[] {"a b", "c\"d", null}; first.numbers = new Integer[] {1, 2};
            manager.persist(first);
            dev.ksql.compatpg.Tagged second = new dev.ksql.compatpg.Tagged();
            manager.persist(second);
            manager.getTransaction().commit();
            org.junit.jupiter.api.Assertions.assertEquals(first.id + 1, second.id);
            manager.clear();
            dev.ksql.compatpg.Tagged read = manager.find(dev.ksql.compatpg.Tagged.class, first.id);
            org.junit.jupiter.api.Assertions.assertArrayEquals(first.tags, read.tags);
            org.junit.jupiter.api.Assertions.assertArrayEquals(first.numbers, read.numbers);
        } finally {
            manager.close();
        }
    }

    /** A PostgreSQL enum column behind a plain Java enum, which pgjdbc needs extra configuration for. */
    @org.junit.jupiter.api.Test void enumColumns() {
        jdbc.execute("drop table if exists moods; drop type if exists mood cascade; create type mood as enum ('SAD', 'HAPPY'); create table moods (id bigserial primary key, mood mood)");
        var manager = entities.getEntityManagerFactory().createEntityManager();
        try {
            manager.getTransaction().begin();
            var entity = new dev.ksql.compatpg.Moody();
            entity.mood = dev.ksql.compatpg.Moody.Mood.HAPPY;
            manager.persist(entity);
            manager.getTransaction().commit();
            manager.clear();
            org.junit.jupiter.api.Assertions.assertEquals(dev.ksql.compatpg.Moody.Mood.HAPPY, manager.find(dev.ksql.compatpg.Moody.class, entity.id).mood);
            org.junit.jupiter.api.Assertions.assertEquals(1, manager.createQuery("select m from Moody m where m.mood = :m", dev.ksql.compatpg.Moody.class)
                .setParameter("m", dev.ksql.compatpg.Moody.Mood.HAPPY).getResultList().size());
        } finally {
            manager.close();
        }
    }

    @org.junit.jupiter.api.Test void storedFunctionCall() {
        jdbc.execute("create or replace function inc(i int) returns int as $$ begin return i + 1; end; $$ language plpgsql");
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(42), jdbc.queryForObject("select inc(?)", Integer.class, 41));
        // CallableStatement isn't implemented, which a procedure call has to say clearly, not fail in some obscure way.
        var query = entities.createStoredProcedureQuery("inc");
        query.registerStoredProcedureParameter(1, Integer.class, jakarta.persistence.ParameterMode.IN);
        query.registerStoredProcedureParameter(2, Integer.class, jakarta.persistence.ParameterMode.OUT);
        query.setParameter(1, 41);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, query::execute);
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("prepareCall is not supported"), failure.getMessage());
    }

    @Override String utcText(String column) { return "to_char(" + column + " at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS')"; }

    @Override Map<String, Object> validationProperties() {
        return Map.of("spring.datasource.url", Databases.postgresUrl(), "spring.datasource.username", "app", "spring.datasource.password", "app",
            "spring.datasource.driver-class-name", KsqlJdbcDriver.class.getName(), "spring.jpa.hibernate.ddl-auto", "validate");
    }
}
