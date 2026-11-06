package ru.ttraum.kotsql.compat;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "authors")
public class Author {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, unique = true, length = 100) public String name;
    @Version public int version;
    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true) public List<Book> books = new ArrayList<>();

    public Author() {}
    public Author(String name) { this.name = name; }
    public Book add(Book book) { book.author = this; books.add(book); return book; }
}
