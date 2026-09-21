// Why this file exists: the business rules, between HTTP (the controller) and SQL (the store):
// demo size limits, retention cleanup, short-code generation with retry, and how a redirect
// decides 404 vs 410. It is the Java counterpart of the Go, Python and C# services'
// business-rule layers, and the contract tests hold all of them to the same behaviour.
package com.resume.links.service;

import com.resume.links.domain.Link;
import com.resume.links.domain.LinkStatus;
import com.resume.links.domain.ShortCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// JS/TS vs Java: @Service registers this class as a BEAN: Spring creates one instance and injects
// it wherever it is asked for. The constructor's parameters are its dependencies, and Spring
// supplies them (constructor injection; no @Autowired needed with a single constructor).
//
// @Transactional on the class wraps every public method in a database transaction, via a PROXY:
// Spring hands out a generated subclass that begins a transaction, calls your method, then
// COMMITS on return (or rolls back if it threw). So a method's statements are atomic together,
// and the commit happens BEFORE the controller returns its response. Gotcha: the proxy only
// intercepts calls from OUTSIDE the class; a method calling another of its own methods bypasses it.
@Service
@Transactional
public class LinkService {

  // Guard rails for a public demo; the same numbers as the other implementations.
  public static final int MAX_LINKS_PER_OWNER = 20;
  public static final int MAX_LINKS_TOTAL = 5000;
  public static final int RETENTION_DAYS = 30;
  private static final int MAX_CODE_ATTEMPTS = 5;

  private static final Map<LinkStatus, String> GONE_MESSAGES =
      // JS/TS vs Java: `Map.of(...)` builds an IMMUTABLE map (also `List.of`, `Set.of`): trying to
      // modify it throws. Java's ordinary collections are mutable by default, so the immutable
      // factories are the way to declare a constant.
      Map.of(
          LinkStatus.EXPIRED, "This link has expired.",
          LinkStatus.MAX_CLICKS, "This link has reached its click limit.",
          LinkStatus.DISABLED, "This link has been deactivated.");

  private final LinkStore store;
  // JS/TS vs Java: java.time.Clock is the built-in clock abstraction (like C#'s TimeProvider):
  // production uses the real one, tests substitute a fixed clock.
  private final Clock clock;

  public LinkService(LinkStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public CreateResult create(String ownerId, CreateLinkInput data) {
    // Lazy cleanup instead of a cron job: whoever creates a link also sweeps expired demo data.
    store.deleteCreatedBefore(clock.instant().minus(Duration.ofDays(RETENTION_DAYS)));

    // Soft limits: count-then-insert can be overshot by a burst of concurrent requests. Fine for
    // abuse control, unlike max_clicks, which is a correctness guarantee and is enforced
    // atomically in SQL.
    if (store.countByOwner(ownerId) >= MAX_LINKS_PER_OWNER) {
      return new CreateResult.LimitReached(
          "Demo limit: " + MAX_LINKS_PER_OWNER + " links per visitor.");
    }
    if (store.countAll() >= MAX_LINKS_TOTAL) {
      return new CreateResult.LimitReached("Demo limit: the service is full right now.");
    }

    // A custom code either works or is "taken"; retrying would not help.
    if (data.shortCode() != null) {
      return store
          .insert(ownerId, data.shortCode(), data)
          .<CreateResult>map(CreateResult.Created::new)
          // JS/TS vs Java: `Optional.orElseGet(supplier)` runs the supplier ONLY when the Optional
          // is empty (`orElse(value)` would always build the value). `.<CreateResult>map` spells
          // out
          // the type argument, because Java can't always infer that both branches are the same
          // sealed type; `CreateResult.Created::new` is a constructor reference (a function value).
          .orElseGet(CreateResult.CodeTaken::new);
    }

    // A generated code that collides is just bad luck: try a fresh one.
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      Optional<Link> link = store.insert(ownerId, ShortCode.generate(), data);
      if (link.isPresent()) {
        return new CreateResult.Created(link.get());
      }
    }
    // An unexpected failure IS thrown; the app's exception handler turns it into a 500.
    throw new IllegalStateException("Could not generate a unique short code");
  }

  // readOnly lets Hibernate skip dirty-checking and lets the database optimise: it's a hint that
  // this method only reads.
  @Transactional(readOnly = true)
  public List<Link> list(String ownerId) {
    return store.listByOwner(ownerId);
  }

  /** Empty when the link does not exist for this owner. */
  public Optional<Link> setActive(String ownerId, String id, boolean active) {
    return store.setActive(ownerId, id, active);
  }

  public FollowResult follow(String code) {
    Optional<ClaimedLink> claimed = store.claim(code);
    if (claimed.isPresent()) {
      return new FollowResult.Followed(claimed.get().linkId(), claimed.get().targetUrl());
    }

    // Nothing was claimed: no such link (404) or it exists but is not redeemable (410). This
    // lookup may be non-atomic because it only chooses the error message; the decision was made
    // atomically above.
    Optional<Link> link = store.findByCode(code);
    if (link.isEmpty()) {
      return new FollowResult.NotFound();
    }

    LinkStatus status = link.get().status(clock.instant());
    // The link changed between the two statements (for example it was re-enabled) if it now looks
    // active: report it as unavailable rather than guess.
    LinkStatus reason = status == LinkStatus.ACTIVE ? LinkStatus.DISABLED : status;
    return new FollowResult.Gone(GONE_MESSAGES.get(reason));
  }

  /** Logs the analytics row. Separate from follow() so it can run off the request thread. */
  public void recordClick(String linkId, String referrer, String userAgent) {
    store.insertClickEvent(linkId, referrer, userAgent);
  }

  /** The moment this service considers "now" (exposed for the controller's DTO mapping). */
  public Instant now() {
    return clock.instant();
  }
}
