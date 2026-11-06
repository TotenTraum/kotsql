package dev.ksql.compatreactive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@EnableR2dbcRepositories(considerNestedRepositories = true)
public class ReactiveApp {
    @Table("items")
    public static class Item {
        @Id public Long id;
        @Version public Long version;
        public String name;
        public BigDecimal price;
        public LocalDate made;
        public LocalDateTime updated;
        public Boolean active;
        public Integer quantity;

        public Item() {}
        public Item(String name, int quantity) {
            this.name = name; this.quantity = quantity; this.price = new BigDecimal("9.50"); this.active = true;
            this.made = LocalDate.of(2024, 2, 29); this.updated = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
        }
    }

    public interface Items extends ReactiveCrudRepository<Item, Long> {
        Flux<Item> findByNameContaining(String part);
        Flux<Item> findByActive(boolean active, Pageable page);
        Mono<Long> countByQuantityGreaterThan(int quantity);

        @Query("select * from items where quantity > :quantity order by quantity desc")
        Flux<Item> withMoreThan(int quantity);

        @Modifying @Query("update items set quantity = quantity + 1 where name like :prefix")
        Mono<Long> addOne(String prefix);
    }
}
