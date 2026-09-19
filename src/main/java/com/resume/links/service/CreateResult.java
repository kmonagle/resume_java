// Why this file exists: the possible outcomes of creating a link, as values, not exceptions. The
// controller decides what each means for the client. Only genuine failures (the database is down)
// are exceptions.
package com.resume.links.service;

import com.resume.links.domain.Link;

// JS/TS vs Java: a SEALED interface lists exactly which types may implement it, so this is Java's
// discriminated union. The compiler knows the full set, and a `switch` over a CreateResult must
// cover every case or it won't compile (see the controller). The three records are nested in the
// interface and implicitly permitted.
public sealed interface CreateResult {

  record Created(Link link) implements CreateResult {}

  record CodeTaken() implements CreateResult {}

  record LimitReached(String message) implements CreateResult {}
}
