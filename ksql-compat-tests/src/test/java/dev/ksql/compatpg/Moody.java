package dev.ksql.compatpg;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "moods")
public class Moody {
    public enum Mood { SAD, HAPPY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) @Column(columnDefinition = "mood") public Mood mood;
}
