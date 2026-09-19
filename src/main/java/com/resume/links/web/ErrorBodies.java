// Why this file exists: the JSON shapes of the contract's error responses, so every endpoint
// answers failures the same way instead of improvising.
package com.resume.links.web;

import java.util.List;
import java.util.Map;

// JS/TS vs Java: there are no free-standing types inside a file or namespace blocks, so a `final`
// class with a private constructor is used purely as a NAMESPACE for related nested types:
// `ErrorBodies.Error`, `ErrorBodies.Validation`. Generics like `Map<String, List<String>>` are
// `Record<string, string[]>`, checked at compile time and erased at runtime.
public final class ErrorBodies {

  private ErrorBodies() {}

  /** {"error": "..."} */
  public record Error(String error) {}

  /** {"error": "Validation failed", "fieldErrors": {"field": ["message"]}} */
  public record Validation(String error, Map<String, List<String>> fieldErrors) {
    public static Validation of(Map<String, List<String>> fieldErrors) {
      return new Validation("Validation failed", fieldErrors);
    }
  }

  /** A body that isn't JSON, or has values of the wrong type, is the CLIENT's mistake: a 400. */
  public static Validation invalidBody() {
    return Validation.of(Map.of("body", List.of("Body must be valid JSON of the expected shape")));
  }
}
