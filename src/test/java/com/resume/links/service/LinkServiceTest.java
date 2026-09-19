// Why this file exists: tests the business rules without a database, using the hand-written
// FakeLinkStore. Because the service depends on an interface, the fake is short.
package com.resume.links.service;

import static com.resume.links.TestSupport.fixedClock;
import static com.resume.links.TestSupport.link;
import static org.assertj.core.api.Assertions.assertThat;

import com.resume.links.TestSupport.FakeLinkStore;
import org.junit.jupiter.api.Test;

class LinkServiceTest {

  private static final CreateLinkInput DATA =
      new CreateLinkInput("https://a.co", null, null, null, null);

  private static LinkService serviceWith(FakeLinkStore store) {
    return new LinkService(store, fixedClock());
  }

  // JS/TS vs Java: `assertThat(x).isInstanceOf(T.class)` checks the runtime type. The result types
  // are sealed records, so `.asInstanceOf(...)`-style unpacking or a pattern cast reads the fields.
  @Test
  void createEnforcesLimits() {
    var perOwner = new FakeLinkStore();
    perOwner.perOwner = LinkService.MAX_LINKS_PER_OWNER;
    assertThat(serviceWith(perOwner).create("o", DATA))
        .isInstanceOf(CreateResult.LimitReached.class);

    var total = new FakeLinkStore();
    total.total = LinkService.MAX_LINKS_TOTAL;
    assertThat(serviceWith(total).create("o", DATA)).isInstanceOf(CreateResult.LimitReached.class);

    assertThat(serviceWith(new FakeLinkStore()).create("o", DATA))
        .isInstanceOf(CreateResult.Created.class);
  }

  @Test
  void aTakenCustomCodeIsReported() {
    var store = new FakeLinkStore();
    store.takenCodes.add("promo");
    var data = new CreateLinkInput("https://a.co", null, null, null, "promo");
    assertThat(serviceWith(store).create("o", data)).isInstanceOf(CreateResult.CodeTaken.class);
  }

  @Test
  void followingAClaimedLinkRedirects() {
    var store = new FakeLinkStore();
    store.claim = new ClaimedLink("id-1", "https://target");
    assertThat(serviceWith(store).follow("x"))
        .isEqualTo(new FollowResult.Followed("id-1", "https://target"));
  }

  @Test
  void anUnknownCodeIsNotFound() {
    assertThat(serviceWith(new FakeLinkStore()).follow("x"))
        .isInstanceOf(FollowResult.NotFound.class);
  }

  @Test
  void aGoneLinkSaysWhy() {
    var store = new FakeLinkStore();
    store.found = link("c", true, null, 1, 1);
    assertThat(serviceWith(store).follow("x"))
        .isEqualTo(new FollowResult.Gone("This link has reached its click limit."));
  }

  @Test
  void aLinkThatLooksActiveAfterAFailedClaimIsNeverRedirected() {
    // The link changed between the two statements: report unavailable, don't guess.
    var store = new FakeLinkStore();
    store.found = link();
    assertThat(serviceWith(store).follow("x")).isInstanceOf(FollowResult.Gone.class);
  }
}
