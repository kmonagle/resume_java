// Why this file exists: the four states a link can be in, and their names on the wire.
package com.resume.links.domain;

// JS/TS vs Java: an enum is a real class with a fixed set of instances, and it can carry data
// and methods (here, the wire name). It is not a string union like TypeScript's
// "active" | "expired": each constant is an object, compared with `==` (safe for enums).
public enum LinkStatus {
  ACTIVE("active"),
  EXPIRED("expired"),
  MAX_CLICKS("max_clicks"),
  DISABLED("disabled");

  private final String wire;

  LinkStatus(String wire) {
    this.wire = wire;
  }

  /** The name the contract uses in JSON. */
  public String wire() {
    return wire;
  }
}
