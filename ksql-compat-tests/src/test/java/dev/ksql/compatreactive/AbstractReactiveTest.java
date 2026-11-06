package dev.ksql.compatreactive;

import static org.junit.jupiter.api.Assertions.*;

import dev.ksql.compatreactive.ReactiveApp.Item;
import dev.ksql.compatreactive.ReactiveApp.Items;
import io.r2dbc.spi.ConnectionFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

abstract class AbstractReactiveTest {
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired Items items;
    @Autowired DatabaseClient client;
    @Autowired ReactiveTransactionManager transactions;
    @Autowired R2dbcEntityTemplate template;
    @Autowired ConnectionFactory factory;

    abstract String idColumn();

    @BeforeEach void schema() {
        client.sql("DROP TABLE IF EXISTS items").then().block(WAIT);
        client.sql("CREATE TABLE items (id " + idColumn() + ", version BIGINT, name VARCHAR(50) UNIQUE, price DECIMAL(10,2), made DATE, updated TIMESTAMP NULL, active BOOLEAN, quantity INT)").then().block(WAIT);
    }

    @Test void pooledConnectionFactory() {
        assertTrue(factory instanceof io.r2dbc.pool.ConnectionPool, factory.getClass().getName());
    }

    @Test void savesAndReadsEntities() {
        Item saved = items.save(new Item("first", 5)).block(WAIT);
        assertNotNull(saved.id); assertTrue(saved.id > 0, "generated id");
        Item read = items.findById(saved.id).block(WAIT);
        assertEquals("first", read.name); assertEquals(0, new BigDecimal("9.50").compareTo(read.price)); assertEquals(saved.made, read.made);
        assertEquals(saved.updated, read.updated); assertTrue(read.active); assertEquals(5, read.quantity);
        read.quantity = 6;
        items.save(read).block(WAIT);
        assertEquals(6, items.findById(saved.id).block(WAIT).quantity);
        items.deleteById(saved.id).block(WAIT);
        assertNull(items.findById(saved.id).block(WAIT));
    }

    @Test void derivedQueriesPagingAndModifying() {
        items.saveAll(Flux.range(1, 25).map(i -> { Item it = new Item("item " + i, i); it.active = i % 2 == 0; return it; })).blockLast(WAIT);
        assertEquals(25, items.count().block(WAIT));
        assertEquals(1, items.findByNameContaining("item 25").collectList().block(WAIT).size());
        assertEquals(5L, items.countByQuantityGreaterThan(20).block(WAIT));
        assertEquals(25, items.withMoreThan(24).blockFirst(WAIT).quantity);
        List<Item> page = items.findByActive(true, PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "quantity"))).collectList().block(WAIT);
        assertEquals(5, page.size()); assertEquals(14, page.get(0).quantity);
        assertEquals(25L, items.addOne("item%").block(WAIT));
    }

    @Test void transactionsCommitAndRollBack() {
        TransactionalOperator tx = TransactionalOperator.create(transactions);
        items.save(new Item("kept", 1)).as(tx::transactional).block(WAIT);
        assertThrows(IllegalStateException.class, () ->
            items.save(new Item("gone", 1)).then(Mono.<Item>error(new IllegalStateException("boom"))).as(tx::transactional).block(WAIT));
        assertEquals(List.of("kept"), items.findAll().map(i -> i.name).collectList().block(WAIT));
    }

    @Test void optimisticLockingAndErrors() {
        Item saved = items.save(new Item("v", 1)).block(WAIT);
        Item stale = items.findById(saved.id).block(WAIT);
        saved.quantity = 2;
        items.save(saved).block(WAIT);
        stale.quantity = 3;
        assertThrows(OptimisticLockingFailureException.class, () -> items.save(stale).block(WAIT));
        assertThrows(DataIntegrityViolationException.class, () -> items.save(new Item("v", 1)).block(WAIT));
        assertThrows(DataAccessException.class, () -> client.sql("select * from no_such_table").fetch().all().collectList().block(WAIT));
        assertEquals(1L, items.count().block(WAIT));
    }

    @Test void concurrentLoadThroughThePool() {
        Long total = Flux.range(1, 300).flatMap(i -> items.save(new Item("c" + i, i)), 32).map(i -> (long) i.quantity).reduce(0L, Long::sum).block(WAIT);
        assertEquals(45150L, total);
        assertEquals(300L, items.count().block(WAIT));
    }
}
