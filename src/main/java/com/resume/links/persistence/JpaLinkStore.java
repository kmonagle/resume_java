// Why this file exists: the ONLY class that talks to the database about links. The service
// depends on the LinkStore interface; this is the real implementation, on top of Spring Data JPA.
// The schema is owned by the Next.js repo's migrations; this service never migrates.
//
// JS/TS vs Java: this is the data layer that a Node app would write with Drizzle or Prisma.
package com.resume.links.persistence;

import com.resume.links.domain.Link;
import com.resume.links.service.ClaimedLink;
import com.resume.links.service.CreateLinkInput;
import com.resume.links.service.LinkStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// @Repository marks this as a data-access bean (and makes Spring translate low-level SQL
// exceptions into its own unchecked hierarchy). It runs inside the transactions that
// LinkService opens.
@Repository
public class JpaLinkStore implements LinkStore {

  private final LinkJpaRepository links;

  // JS/TS vs Java: the EntityManager is JPA's unit of work (like a session): it tracks the
  // entities you load and writes changes at commit. It is injected as a shared proxy that always
  // resolves to the current transaction's manager.
  @PersistenceContext private EntityManager entityManager;

  public JpaLinkStore(LinkJpaRepository links) {
    this.links = links;
  }

  // JS/TS vs Java: @Override asks the compiler to CHECK that this method really implements one from
  // the interface, so a typo in the name or a parameter becomes a compile error instead of a
  // silently separate method. It is optional but always written.
  @Override
  public Optional<Link> insert(String ownerId, String code, CreateLinkInput data) {
    int inserted =
        links.insertIfAbsent(
            UUID.randomUUID().toString(),
            code,
            data.targetUrl(),
            data.title(),
            ownerId,
            data.expiresAt(),
            data.maxClicks());
    if (inserted == 0) {
      return Optional.empty(); // the code was taken
    }
    // JS/TS vs Java: Optional has map/filter like a one-element array: this reads the row back
    // (with the defaults the database chose) and converts it, or stays empty.
    return links.findByShortCode(code).map(JpaLinkStore::toLink);
  }

  @Override
  public List<Link> listByOwner(String ownerId) {
    return links.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
        .map(JpaLinkStore::toLink)
        .toList();
  }

  @Override
  public long countByOwner(String ownerId) {
    return links.countByOwnerId(ownerId);
  }

  @Override
  public long countAll() {
    return links.count();
  }

  @Override
  public void deleteCreatedBefore(Instant cutoff) {
    links.deleteCreatedBefore(cutoff);
  }

  // Scoped by owner: another owner's id matches nothing, so the caller sees "not found" and can
  // never toggle a link it does not own.
  @Override
  public Optional<Link> setActive(String ownerId, String id, boolean active) {
    return links
        .findByIdAndOwnerId(id, ownerId)
        .map(
            entity -> {
              // Dirty checking in action: we only CHANGE the managed entity; Hibernate notices and
              // writes an UPDATE when the transaction commits (and sets updated_at from the DB
              // clock via @CurrentTimestamp). There is no explicit save call.
              entity.setActive(active);
              return toLink(entity);
            });
  }

  @Override
  public Optional<ClaimedLink> claim(String code) {
    if (links.claim(code) == 0) {
      return Optional.empty();
    }
    return links
        .findByShortCode(code)
        .map(entity -> new ClaimedLink(entity.getId(), entity.getTargetUrl()));
  }

  @Override
  public Optional<Link> findByCode(String code) {
    return links.findByShortCode(code).map(JpaLinkStore::toLink);
  }

  @Override
  public void insertClickEvent(String linkId, String referrer, String userAgent) {
    // persist() inserts directly. (save() would, for an entity with an assigned id, run a SELECT
    // first to decide between insert and update.)
    entityManager.persist(new ClickEventEntity(linkId, referrer, userAgent));
  }

  // Map the entity to the domain record, so nothing above the store ever sees a managed entity.
  private static Link toLink(LinkEntity e) {
    return new Link(
        e.getId(),
        e.getShortCode(),
        e.getTargetUrl(),
        e.getTitle(),
        e.getCreatedAt(),
        e.getExpiresAt(),
        e.getMaxClicks(),
        e.getClickCount(),
        e.isActive());
  }
}
