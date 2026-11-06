package ru.ttraum.kotsql.compat;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.query.Param;

@SpringBootApplication(exclude = {R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class, R2dbcRepositoriesAutoConfiguration.class, R2dbcTransactionManagerAutoConfiguration.class})
@EnableJpaRepositories(considerNestedRepositories = true)
public class CompatApp {
    public interface Authors extends JpaRepository<Author, Long> {
        Optional<Author> findByName(String name);

        @EntityGraph(attributePaths = "books")
        @Query("select a from Author a where a.name = :name")
        Optional<Author> withBooks(@Param("name") String name);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select a from Author a where a.id = :id")
        Optional<Author> lockById(@Param("id") Long id);
    }

    public interface TitleOnly { String getTitle(); BigDecimal getPrice(); }

    public interface Books extends JpaRepository<Book, Long> {
        List<Book> findByPriceGreaterThanOrderByPriceDesc(BigDecimal price);
        List<Book> findByTitleContainingIgnoreCase(String part);
        List<TitleOnly> findByInPrint(boolean inPrint);
        Page<Book> findByAuthorName(String name, Pageable pageable);
        long countByGenre(Book.Genre genre);

        @Modifying @Query("update Book b set b.pages = b.pages + 1 where b.title like :prefix")
        int addPage(@Param("prefix") String prefix);

        @Query(value = "select title from books where pages > ?1 order by title", nativeQuery = true)
        List<String> titlesWithMorePagesThan(int pages);

        @Query("select b.genre as genre, count(b) as total, sum(b.price) as sum from Book b group by b.genre order by b.genre")
        List<Object[]> statistics();
    }
}
