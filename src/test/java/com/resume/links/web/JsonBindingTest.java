// Why this file exists: documents WHY the request records use `Object` for numeric and boolean
// fields. Jackson's default is to be forgiving (turning 1.5 into 1, or "5" into 5), which would let
// input through that the contract says must be rejected. If a Jackson upgrade ever changes these
// defaults, this test says so, and the workaround can be revisited.
package com.resume.links.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JsonBindingTest {

  // JS/TS vs Java: a record nested in the test, declared only for this test's needs. Jackson 3 (the
  // `tools.jackson` package) is the JSON library Spring Boot 4 uses; it fills a record from JSON by
  // matching names to the record's components.
  record Typed(Integer maxClicks, Boolean isActive) {}

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void anIntegerFieldSilentlyAcceptsWhatTheContractRejects() {
    Typed decimal = mapper.readValue("{\"maxClicks\": 1.5}", Typed.class);
    Typed text = mapper.readValue("{\"maxClicks\": \"5\", \"isActive\": \"true\"}", Typed.class);
    // 1.5 became 1, "5" became 5, and the string "true" became a boolean.
    assertThat(decimal.maxClicks()).isEqualTo(1);
    assertThat(text.maxClicks()).isEqualTo(5);
    assertThat(text.isActive()).isTrue();
  }

  @Test
  void anObjectFieldKeepsTheRealJsonType() {
    CreateLinkRequest decimal = mapper.readValue("{\"maxClicks\": 1.5}", CreateLinkRequest.class);
    CreateLinkRequest text = mapper.readValue("{\"maxClicks\": \"5\"}", CreateLinkRequest.class);
    CreateLinkRequest bool = mapper.readValue("{\"maxClicks\": true}", CreateLinkRequest.class);
    CreateLinkRequest whole = mapper.readValue("{\"maxClicks\": 5}", CreateLinkRequest.class);

    assertThat(decimal.maxClicks()).isInstanceOf(Double.class);
    assertThat(text.maxClicks()).isInstanceOf(String.class);
    assertThat(bool.maxClicks()).isInstanceOf(Boolean.class);
    assertThat(whole.maxClicks()).isInstanceOf(Integer.class);
  }
}
