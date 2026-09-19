// Why this file exists: builds the database connection pool from the DATABASE_URL environment
// variable. Spring Boot would normally configure this from `spring.datasource.*` properties, but
// those expect a JDBC URL with separate credentials, so we build the pool ourselves from the
// URL Neon hands out (see DatabaseUrl).
package com.resume.links.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// JS/TS vs Java: a @Configuration class is a factory of beans: each @Bean method builds one object
// and hands it to Spring's container, which injects it wherever it is needed (here, into JPA).
// Spring Boot's own DataSource auto-configuration notices this bean exists and backs off.
@Configuration
public class DataSourceConfig {

  // `${DATABASE_URL}` reads the environment variable; the app fails to start with a clear
  // "could not resolve placeholder" message if it is missing.
  @Bean
  public DataSource dataSource(@Value("${DATABASE_URL}") String databaseUrl) {
    DatabaseUrl parsed = DatabaseUrl.parse(databaseUrl);

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(parsed.jdbcUrl());
    config.setUsername(parsed.username());
    config.setPassword(parsed.password());

    // Small pool: several services share one database.
    config.setMaximumPoolSize(5);
    // -1 = don't fail the whole application if the database can't be reached at startup. The
    // pool connects lazily, so the service starts (and answers /meta) even while a scale-to-zero
    // Neon database is waking.
    config.setInitializationFailTimeout(-1);

    // HikariCP is the standard JDBC connection pool (Spring Boot's default).
    return new HikariDataSource(config);
  }
}
