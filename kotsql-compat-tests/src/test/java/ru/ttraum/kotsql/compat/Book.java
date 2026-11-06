package ru.ttraum.kotsql.compat;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Every kind of value that applications commonly map. */
@Entity
@Table(name = "books", indexes = @Index(name = "books_title", columnList = "title"))
public class Book {
    public enum Genre { NOVEL, ESSAY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String title;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) public Author author;
    @Column(precision = 10, scale = 2) public BigDecimal price;
    public Integer pages;
    public Long sold;
    public Double rating;
    public Float weight;
    public Short edition;
    public Boolean inPrint;
    public LocalDate published;
    public LocalTime openingAt;
    public LocalDateTime updatedLocal;
    public Instant updatedInstant;
    public OffsetDateTime updatedOffset;
    @Column(name = "ref") @JdbcTypeCode(SqlTypes.UUID) public UUID ref;
    @Enumerated(EnumType.STRING) @Column(length = 10) public Genre genre;
    @Enumerated(EnumType.ORDINAL) @Column(name = "genre_ordinal") public Genre genreOrdinal;
    @Column(length = 1000) public byte[] cover;
    @Lob @Column(columnDefinition = "TEXT") public String text;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, Object> attributes;

    public Book() {}
    public Book(String title) { this.title = title; }
}
