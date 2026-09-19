// Why this file exists: the URL Neon hands out does not work with the JDBC driver as-is. These
// tests pin down the conversion, so a change can't quietly break production.
package com.resume.links.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

  private static final String NEON =
      "postgres://user:p%40ss@ep-x-pooler.neon.tech/db?sslmode=require&channel_binding=require";

  @Test
  void urlIsConvertedToAJdbcUrlWithSeparateCredentials() {
    DatabaseUrl parsed = DatabaseUrl.parse(NEON);
    assertThat(parsed.jdbcUrl()).startsWith("jdbc:postgresql://ep-x-pooler.neon.tech:5432/db?");
    assertThat(parsed.username()).isEqualTo("user");
    assertThat(parsed.password()).isEqualTo("p@ss"); // percent-decoded
  }

  @Test
  void sslmodeIsKeptAndChannelBindingDropped() {
    String jdbc = DatabaseUrl.parse(NEON).jdbcUrl();
    assertThat(jdbc).contains("sslmode=require").doesNotContain("channel_binding");
  }

  @Test
  void preparedStatementsAreDisabledForPgBouncer() {
    assertThat(DatabaseUrl.parse(NEON).jdbcUrl()).contains("prepareThreshold=0");
  }

  @Test
  void explicitPortIsKeptAndNoSslmodeIsAddedWhenAbsent() {
    String jdbc = DatabaseUrl.parse("postgres://u:p@localhost:54329/links").jdbcUrl();
    assertThat(jdbc)
        .startsWith("jdbc:postgresql://localhost:54329/links?")
        .doesNotContain("sslmode");
  }

  @Test
  void aJdbcUrlPassesThroughUnchanged() {
    DatabaseUrl parsed = DatabaseUrl.parse("jdbc:postgresql://h/d");
    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://h/d");
    assertThat(parsed.username()).isNull();
  }
}
