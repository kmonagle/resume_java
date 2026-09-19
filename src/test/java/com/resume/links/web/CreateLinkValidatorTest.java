// Why this file exists: validation is the trust boundary for user input. These tests cover the
// security-relevant cases (URL schemes) and the form-specific ones (blank fields, code format,
// expiry), matching the Go, Python, C# and Next.js suites.
package com.resume.links.web;

import static com.resume.links.TestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreateLinkValidatorTest {

  private static final String OK = "https://example.com/path";

  private static CreateLinkValidator.Result validate(
      String url, String title, String expiresAt, Object maxClicks, String shortCode) {
    return CreateLinkValidator.validate(
        new CreateLinkRequest(url, title, expiresAt, maxClicks, shortCode), NOW);
  }

  private static CreateLinkValidator.Result withUrl(String url) {
    return validate(url, null, null, null, null);
  }

  @Test
  void acceptsAMinimalLink() {
    var result = withUrl(OK);
    assertThat(result.isValid()).isTrue();
    assertThat(result.input().targetUrl()).isEqualTo(OK);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"javascript:alert(1)", "data:text/html,hi", "ftp://x.com/a", "nope", "http://"})
  void rejectsNonHttpTargets(String url) {
    assertThat(withUrl(url).errors()).containsKey("targetUrl");
  }

  @Test
  void targetUrlIsRequired() {
    assertThat(withUrl(null).errors().get("targetUrl")).containsExactly("Required");
  }

  @Test
  void blankOptionalFieldsAreTreatedAsAbsent() {
    var result = validate(OK, "  ", "", "", "");
    assertThat(result.isValid()).isTrue();
    assertThat(result.input().title()).isNull();
    assertThat(result.input().expiresAt()).isNull();
    assertThat(result.input().maxClicks()).isNull();
    assertThat(result.input().shortCode()).isNull();
  }

  @Test
  void titleIsTrimmedAndCapped() {
    assertThat(validate(OK, "  Docs  ", null, null, null).input().title()).isEqualTo("Docs");
    assertThat(validate(OK, "x".repeat(101), null, null, null).errors()).containsKey("title");
  }

  @Test
  void titleLengthCountsCharactersNotUtf16Units() {
    // 100 emoji is 200 UTF-16 units but 100 characters: still valid.
    assertThat(validate(OK, "😀".repeat(100), null, null, null).isValid()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 2_000_000})
  void maxClicksMustBeInRange(int value) {
    assertThat(validate(OK, null, null, value, null).errors()).containsKey("maxClicks");
  }

  @Test
  void maxClicksMustBeARealWholeNumber() {
    // What Jackson produces for 1.5, "5" and true respectively.
    for (Object bad : new Object[] {1.5, "abc", true}) {
      assertThat(validate(OK, null, null, bad, null).errors()).containsKey("maxClicks");
    }
    assertThat(validate(OK, null, null, 5, null).input().maxClicks()).isEqualTo(5);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "has space", "a/b/c"})
  void rejectsBadShortCodes(String code) {
    assertThat(validate(OK, null, null, null, code).errors()).containsKey("shortCode");
  }

  @ParameterizedTest
  @ValueSource(strings = {"my-promo_1", "abc"})
  void acceptsGoodShortCodes(String code) {
    assertThat(validate(OK, null, null, null, code).input().shortCode()).isEqualTo(code);
  }

  @Test
  void expiryMustBeAnIsoDatetimeInTheFuture() {
    Instant future = NOW.plusSeconds(3600);
    Instant past = NOW.minusSeconds(3600);
    assertThat(validate(OK, null, future.toString(), null, null).input().expiresAt())
        .isEqualTo(future);
    assertThat(validate(OK, null, past.toString(), null, null).errors()).containsKey("expiresAt");
    // No time and no offset: rejected.
    assertThat(validate(OK, null, "2030-01-01", null, null).errors()).containsKey("expiresAt");
  }
}
