// Why this file exists: pins down the domain rules (status precedence, code generation) that must
// match the contract and the other implementations.
package com.resume.links.domain;

import static com.resume.links.TestSupport.NOW;
import static com.resume.links.TestSupport.link;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// JS/TS vs Java: JUnit 5 is the standard test framework (Jest's role). A test is a method marked
// @Test, found by the build (`mvn test`); there is no `describe`/`it` nesting, the class is the
// group. @ParameterizedTest + @CsvSource is Jest's `test.each`. AssertJ's fluent
// `assertThat(actual).isEqualTo(expected)` reads like a Jest `expect` chain.
// JS/TS vs Java: the class and its test methods are package-private (no `public`): JUnit 5 doesn't
// need it. `import static ...` brings a static method into scope by name, like a named import of
// a function, which is why `assertThat(...)` and `link(...)` appear without a class prefix.
class LinkTest {

  private static final Instant PAST = NOW.minusSeconds(3600);

  @Test
  void isActiveByDefault() {
    assertThat(link().status(NOW)).isEqualTo(LinkStatus.ACTIVE);
  }

  @Test
  void disabledBeatsExpired() {
    assertThat(link("c", false, PAST, null, 0).status(NOW)).isEqualTo(LinkStatus.DISABLED);
  }

  @Test
  void isExpiredOnceExpiryHasPassed() {
    assertThat(link("c", true, PAST, null, 0).status(NOW)).isEqualTo(LinkStatus.EXPIRED);
  }

  @ParameterizedTest
  @CsvSource({"2,ACTIVE", "3,MAX_CLICKS", "4,MAX_CLICKS"})
  void clickLimitIsReachedExactlyAtTheLimit(int clicks, LinkStatus expected) {
    assertThat(link("c", true, null, 3, clicks).status(NOW)).isEqualTo(expected);
  }

  @Test
  void expiredBeatsMaxClicks() {
    assertThat(link("c", true, PAST, 1, 1).status(NOW)).isEqualTo(LinkStatus.EXPIRED);
  }

  @ParameterizedTest
  @CsvSource({"ACTIVE,active", "EXPIRED,expired", "MAX_CLICKS,max_clicks", "DISABLED,disabled"})
  void statusHasTheContractsWireNames(LinkStatus status, String wire) {
    assertThat(status.wire()).isEqualTo(wire);
  }

  @Test
  void generatedCodesAreSevenBase62CharactersAndRandom() {
    Set<String> codes = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      codes.add(ShortCode.generate());
    }
    assertThat(codes).allMatch(code -> Pattern.matches("[0-9A-Za-z]{7}", code));
    // 62^7 possibilities: a collision within 200 draws would mean a broken RNG.
    assertThat(codes).hasSize(200);
  }
}
