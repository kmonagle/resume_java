// Why this file exists: the seam between the business rules (LinkService) and the database.
// The service depends on this interface, not on JPA, so its tests hand it a small hand-written
// fake. JpaLinkStore is the real implementation.
package com.resume.links.service;

import com.resume.links.domain.Link;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// JS/TS vs Java: an interface is a contract that classes must explicitly `implements`
// (nominal typing, like C#; unlike TypeScript's and Go's structural interfaces). Methods that may
// have no result return `Optional<T>`, Java's explicit "might be absent" wrapper, instead of null.
public interface LinkStore {

  /** Returns the new link, or empty when the short code is already taken. */
  Optional<Link> insert(String ownerId, String code, CreateLinkInput data);

  List<Link> listByOwner(String ownerId);

  long countByOwner(String ownerId);

  long countAll();

  void deleteCreatedBefore(Instant cutoff);

  /** Empty when the link doesn't exist for this owner. */
  Optional<Link> setActive(String ownerId, String id, boolean active);

  /** Atomically checks the redeemability rules and counts the click; empty if not redeemable. */
  Optional<ClaimedLink> claim(String code);

  Optional<Link> findByCode(String code);

  void insertClickEvent(String linkId, String referrer, String userAgent);
}
