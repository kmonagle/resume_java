// Why this file exists: the wire format for a link (the contract's `Link` schema).
package com.resume.links.web;

import com.resume.links.domain.Link;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// JS/TS vs Java: a separate record from the domain `Link` on purpose (the same split as
// toLinkDto() in the Next.js repo): the domain type holds real Instants, the wire type holds ISO
// strings. Jackson turns the component names into camelCase JSON keys, and writes null fields as
// null (never omitted), which the Next.js side's zod schema requires.
public record LinkDto(
    String id,
    String shortCode,
    String targetUrl,
    String title,
    String createdAt,
    String expiresAt,
    Integer maxClicks,
    int clickCount,
    boolean isActive,
    String status) {

  // Millisecond precision, UTC, "Z" suffix: identical to JavaScript's Date.toISOString(), so every
  // backend serialises timestamps the same way. (DateTimeFormatter is immutable and thread-safe.)
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static LinkDto from(Link link, Instant now) {
    return new LinkDto(
        link.id(),
        link.shortCode(),
        link.targetUrl(),
        link.title(),
        ISO_MILLIS.format(link.createdAt()),
        link.expiresAt() == null ? null : ISO_MILLIS.format(link.expiresAt()),
        link.maxClicks(),
        link.clickCount(),
        link.isActive(),
        link.status(now).wire());
  }
}
