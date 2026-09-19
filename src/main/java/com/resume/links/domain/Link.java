// Why this file exists: the domain model, free of HTTP and SQL. It holds the one rule every
// implementation of the contract must agree on: "is this link usable, and if not, why?". The
// SQL in JpaLinkStore's claim query applies the same rules; keep them in step.
package com.resume.links.domain;

import java.time.Instant;

// JS/TS vs Java: a RECORD is an immutable data class in one line: the compiler generates the
// constructor, the accessors (`link.shortCode()`, no `get` prefix), and value-based `equals`,
// `hashCode` and `toString`. Records can't be JPA entities (those need mutable, no-arg classes),
// which is why the database layer has separate entity classes and maps to this one.
// `Instant` is a moment in time (UTC), Java's Date; `Integer` (capital I) is the NULLABLE box
// around `int`, used here because max_clicks and the timestamps may be absent.
public record Link(
    String id,
    String shortCode,
    String targetUrl,
    String title,
    Instant createdAt,
    Instant expiresAt,
    Integer maxClicks,
    int clickCount,
    boolean isActive) {

  /**
   * Mirrors the contract's precedence: disabled, then expired, then max_clicks, otherwise active.
   * {@code now} is a parameter (not Instant.now() inside) so the method is deterministic and
   * trivial to test.
   */
  public LinkStatus status(Instant now) {
    if (!isActive) {
      return LinkStatus.DISABLED;
    }
    // JS/TS vs Java: nothing stops a reference being null, so nullable fields are checked by
    // hand. `isBefore` compares instants (never use < on objects: it doesn't compile).
    if (expiresAt != null && expiresAt.isBefore(now)) {
      return LinkStatus.EXPIRED;
    }
    if (maxClicks != null && clickCount >= maxClicks) {
      return LinkStatus.MAX_CLICKS;
    }
    return LinkStatus.ACTIVE;
  }
}
