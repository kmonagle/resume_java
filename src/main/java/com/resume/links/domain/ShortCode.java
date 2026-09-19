// Why this file exists: generating short codes. SecureRandom is a cryptographically secure source,
// which makes codes unguessable; java.util.Random is fast but predictable.
package com.resume.links.domain;

import java.security.SecureRandom;

// JS/TS vs Java: the same split as crypto.getRandomValues (secure) versus Math.random (not).
// Picking the wrong class is the classic security mistake, so the one used below is worth a glance.
public final class ShortCode {

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int LENGTH = 7;
  // One shared instance: SecureRandom is thread-safe, and creating one per call is wasteful.
  private static final SecureRandom RANDOM = new SecureRandom();

  // JS/TS vs Java: a private constructor plus `final` makes this a utility class: it can't be
  // instantiated or subclassed. `static` members belong to the class itself, like module-level
  // functions in JS.
  private ShortCode() {}

  public static String generate() {
    // `nextInt(n)` is unbiased in [0, n), so there is no modulo bias from `byte % 62`.
    // StringBuilder builds a string efficiently; Java strings are immutable, so `+=` in a loop
    // would copy the string every time.
    var code = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
