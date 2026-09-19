// Why this file exists: small helpers shared by the tests: a fixed clock, a link builder, and a
// hand-written fake of the LinkStore, so the tests need no database and no mocking library.
//
// JS/TS vs Java: in Jest you'd `jest.mock` a module. Java's usual answer is Mockito (and it is used
// for the web-layer test), but where the collaborator is a small interface a hand-written fake is
// clearer: no reflection, and the behaviour is right there in the test. The fake says
// `implements LinkStore` because Java interfaces are nominal.
package com.resume.links;

import com.resume.links.domain.Link;
import com.resume.links.service.ClaimedLink;
import com.resume.links.service.CreateLinkInput;
import com.resume.links.service.LinkStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class TestSupport {

  private TestSupport() {}

  public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  public static Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  // JS/TS vs Java: Java has no default or named arguments, so test data builders use OVERLOADS or
  // a small builder. This one takes just the fields the tests vary.
  public static Link link(
      String code, boolean isActive, Instant expiresAt, Integer maxClicks, int clickCount) {
    return new Link(
        "1", code, "https://example.com", null, NOW, expiresAt, maxClicks, clickCount, isActive);
  }

  // JS/TS vs Java: an OVERLOAD: two methods with the same name and different parameters. The
  // compiler picks by the arguments, so `link()` is the short form of the five-argument one.
  public static Link link() {
    return link("abc1234", true, null, null, 0);
  }

  /**
   * A store whose behaviour is set through its fields; anything left alone means "nothing special".
   */
  public static final class FakeLinkStore implements LinkStore {
    public long perOwner;
    public long total;
    public Set<String> takenCodes = new HashSet<>();
    public Link found;
    public ClaimedLink claim;

    @Override
    public Optional<Link> insert(String ownerId, String code, CreateLinkInput data) {
      return takenCodes.contains(code)
          ? Optional.empty()
          : Optional.of(link(code, true, null, null, 0));
    }

    @Override
    public List<Link> listByOwner(String ownerId) {
      return List.of();
    }

    @Override
    public long countByOwner(String ownerId) {
      return perOwner;
    }

    @Override
    public long countAll() {
      return total;
    }

    @Override
    public void deleteCreatedBefore(Instant cutoff) {}

    @Override
    public Optional<Link> setActive(String ownerId, String id, boolean active) {
      return Optional.empty();
    }

    @Override
    public Optional<ClaimedLink> claim(String code) {
      return Optional.ofNullable(claim);
    }

    @Override
    public Optional<Link> findByCode(String code) {
      return Optional.ofNullable(found);
    }

    @Override
    public void insertClickEvent(String linkId, String referrer, String userAgent) {}
  }
}
