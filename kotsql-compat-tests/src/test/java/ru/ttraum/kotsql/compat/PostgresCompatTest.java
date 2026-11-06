package ru.ttraum.kotsql.compat;

import ru.ttraum.kotsql.jdbc.KotsqlJdbcDriver;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = {CompatApp.class, ru.ttraum.kotsql.compatpg.PostgresEntities.class}, properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.jpa.properties.hibernate.jdbc.batch_size=50"})
class PostgresCompatTest extends AbstractCompatTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Databases::postgresUrl);
        r.add("spring.datasource.username", () -> "app");
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.datasource.driver-class-name", KotsqlJdbcDriver.class::getName);
    }

    @org.junit.jupiter.api.Test void sequencesAndArrays() {
        var manager = entities.getEntityManagerFactory().createEntityManager();
        try {
            manager.getTransaction().begin();
            ru.ttraum.kotsql.compatpg.Tagged first = new ru.ttraum.kotsql.compatpg.Tagged();
            first.tags = new String[] {"a b", "c\"d", null}; first.numbers = new Integer[] {1, 2};
            manager.persist(first);
            ru.ttraum.kotsql.compatpg.Tagged second = new ru.ttraum.kotsql.compatpg.Tagged();
            manager.persist(second);
            manager.getTransaction().commit();
            org.junit.jupiter.api.Assertions.assertEquals(first.id + 1, second.id);
            manager.clear();
            ru.ttraum.kotsql.compatpg.Tagged read = manager.find(ru.ttraum.kotsql.compatpg.Tagged.class, first.id);
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
            var entity = new ru.ttraum.kotsql.compatpg.Moody();
            entity.mood = ru.ttraum.kotsql.compatpg.Moody.Mood.HAPPY;
            manager.persist(entity);
            manager.getTransaction().commit();
            manager.clear();
            org.junit.jupiter.api.Assertions.assertEquals(ru.ttraum.kotsql.compatpg.Moody.Mood.HAPPY, manager.find(ru.ttraum.kotsql.compatpg.Moody.class, entity.id).mood);
            org.junit.jupiter.api.Assertions.assertEquals(1, manager.createQuery("select m from Moody m where m.mood = :m", ru.ttraum.kotsql.compatpg.Moody.class)
                .setParameter("m", ru.ttraum.kotsql.compatpg.Moody.Mood.HAPPY).getResultList().size());
        } finally {
            manager.close();
        }
    }

    @org.junit.jupiter.api.Test void storedFunctionCall() {
        jdbc.execute("create or replace function inc(i int) returns int as $$ begin return i + 1; end; $$ language plpgsql");
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(42), jdbc.queryForObject("select inc(?)", Integer.class, 41));
        // Hibernate's way to a function, which goes through CallableStatement.
        jdbc.execute("create or replace procedure inc_out(in i int, out o int) language plpgsql as $$ begin o := i + 1; end; $$");
        var query = entities.createStoredProcedureQuery("inc_out");
        query.registerStoredProcedureParameter(1, Integer.class, jakarta.persistence.ParameterMode.IN);
        query.registerStoredProcedureParameter(2, Integer.class, jakarta.persistence.ParameterMode.OUT);
        query.setParameter(1, 41);
        query.execute();
        org.junit.jupiter.api.Assertions.assertEquals(42, query.getOutputParameterValue(2));
    }

    @Override String utcText(String column) { return "to_char(" + column + " at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS')"; }

    @Override Map<String, Object> validationProperties() {
        return Map.of("spring.datasource.url", Databases.postgresUrl(), "spring.datasource.username", "app", "spring.datasource.password", "app",
            "spring.datasource.driver-class-name", KotsqlJdbcDriver.class.getName(), "spring.jpa.hibernate.ddl-auto", "validate");
    }
}
