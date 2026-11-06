package dev.ksql.compatpg;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EntityScan(basePackages = {"dev.ksql.compat", "dev.ksql.compatpg"})
public class PostgresEntities {}
