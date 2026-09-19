// Why this file exists: validation of POST /links, the trust boundary for user input. The rules
// and messages match docs/openapi.yaml and the Next.js, Go, Python and C# implementations exactly,
// because the contract tests hold every backend to the same behaviour.
//
// JS/TS vs Java: this is the counterpart of src/shared/schemas/link-schema.ts (zod). Java's
// standard answer is Bean Validation (@NotNull, @Size...), which is used for the startup
// settings in config/LinkProperties. Here the rules are written out plainly instead: the messages
// and the blank-means-absent behaviour must match the other implementations word for word, and
// plain code is unit-testable with no framework involved.
package com.resume.links.web;

import com.resume.links.service.CreateLinkInput;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CreateLinkValidator {

  private static final int MAX_URL_LENGTH = 2048;
  private static final int MAX_TITLE_LENGTH = 100;
  private static final int MAX_MAX_CLICKS = 1_000_000;

  // JS/TS vs Java: Pattern.compile builds the regex ONCE (a `static final` field is a constant
  // initialised when the class loads). `matcher(s).matches()` requires the WHOLE string to match,
  // which is why there are no ^ and $ anchors; `find()` would search for a match anywhere.
  private static final Pattern SHORT_CODE = Pattern.compile("[A-Za-z0-9_-]{3,32}");
  // Requires an explicit UTC offset (or Z), so the moment is unambiguous.
  private static final Pattern RFC_3339 =
      Pattern.compile(
          "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})");

  private CreateLinkValidator() {}

  /** The outcome: either a valid input (errors empty) or the field errors (input null). */
  public record Result(CreateLinkInput input, Map<String, List<String>> errors) {
    public boolean isValid() {
      return errors.isEmpty();
    }
  }

  public static Result validate(CreateLinkRequest request, Instant now) {
    // LinkedHashMap keeps insertion order, so error output is stable.
    Map<String, List<String>> errors = new LinkedHashMap<>();

    String targetUrl = null;
    if (request.targetUrl() == null) {
      addError(errors, "targetUrl", "Required");
    } else if (!isHttpUrl(request.targetUrl())) {
      addError(errors, "targetUrl", "Enter a valid http(s) URL");
    } else {
      targetUrl = request.targetUrl();
    }

    String title = null;
    if (!isBlank(request.title())) {
      title = request.title().trim();
      // JS/TS vs Java: String.length() counts UTF-16 units (like JS's .length), so an emoji counts
      // as 2. codePointCount counts what a human sees, and matches the other implementations.
      if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
        addError(errors, "title", "Too long");
        title = null;
      }
    }

    Instant expiresAt = null;
    if (!isBlank(request.expiresAt())) {
      String text = request.expiresAt().trim();
      try {
        if (!RFC_3339.matcher(text).matches()) {
          throw new DateTimeParseException("not RFC 3339", text, 0);
        }
        // OffsetDateTime keeps the offset the caller sent; toInstant() normalises it to UTC.
        Instant parsed = OffsetDateTime.parse(text).toInstant();
        if (!parsed.isAfter(now)) {
          addError(errors, "expiresAt", "Expiry must be in the future");
        } else {
          expiresAt = parsed;
        }
      } catch (DateTimeParseException e) {
        addError(errors, "expiresAt", "Enter a valid date and time");
      }
    }

    Integer maxClicks = null;
    Object rawMaxClicks = request.maxClicks();
    if (rawMaxClicks != null && !(rawMaxClicks instanceof String s && s.isBlank())) {
      // JS/TS vs Java: `instanceof Integer i` is a PATTERN: it tests the runtime type and binds
      // the value in one step. Jackson hands whole numbers over as Integer (or Long when big),
      // decimals as Double, and true/false as Boolean, so anything else is not a whole number.
      // JS/TS vs Java: `long` and `int` are PRIMITIVES (fixed size, never null, no methods); `Long`
      // and `Integer` are the OBJECT wrappers that can be null and live in collections. Java
      // converts
      // between them automatically (AUTOBOXING), which is convenient and a classic source of bugs:
      // comparing two `Integer` objects with == compares references, not values. Narrowing back
      // (`(int) value`) needs an explicit cast, because a long might not fit.
      long value;
      if (rawMaxClicks instanceof Integer i) {
        value = i;
      } else if (rawMaxClicks instanceof Long l) {
        value = l;
      } else {
        value = Long.MIN_VALUE;
        addError(errors, "maxClicks", "Enter a whole number");
      }
      if (value != Long.MIN_VALUE) {
        if (value < 1) {
          addError(errors, "maxClicks", "Must be at least 1");
        } else if (value > MAX_MAX_CLICKS) {
          addError(errors, "maxClicks", "Must be at most 1,000,000");
        } else {
          maxClicks = (int) value;
        }
      }
    }

    String shortCode = null;
    if (!isBlank(request.shortCode())) {
      String code = request.shortCode().trim();
      if (SHORT_CODE.matcher(code).matches()) {
        shortCode = code;
      } else {
        addError(errors, "shortCode", "3-32 characters: letters, numbers, - and _");
      }
    }

    if (!errors.isEmpty()) {
      return new Result(null, errors);
    }
    return new Result(
        new CreateLinkInput(targetUrl, title, expiresAt, maxClicks, shortCode), errors);
  }

  private static void addError(Map<String, List<String>> errors, String field, String message) {
    // computeIfAbsent returns the existing list or creates and stores a new one.
    errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
  }

  // HTML forms send "" for untouched fields; treat "" and whitespace as absent.
  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isHttpUrl(String value) {
    // Only http(s): a shortener that redirects to `javascript:` or `data:` URLs is an XSS gadget.
    if (value.length() > MAX_URL_LENGTH) {
      return false;
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      return scheme != null
          && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && uri.getHost() != null
          && !uri.getHost().isEmpty();
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
