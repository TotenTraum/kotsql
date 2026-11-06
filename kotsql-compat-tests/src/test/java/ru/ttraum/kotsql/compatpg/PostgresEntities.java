package ru.ttraum.kotsql.compatpg;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EntityScan(basePackages = {"ru.ttraum.kotsql.compat", "ru.ttraum.kotsql.compatpg"})
public class PostgresEntities {}
