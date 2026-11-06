package dev.ksql.compatpg;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Features that only exist on PostgreSQL, in a package that only the PostgreSQL test scans. */
@Entity
@Table(name = "tagged")
public class Tagged {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tagged_seq")
    @SequenceGenerator(name = "tagged_seq", sequenceName = "tagged_seq", allocationSize = 5)
    public Long id;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") public String[] tags;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "int[]") public Integer[] numbers;
}
