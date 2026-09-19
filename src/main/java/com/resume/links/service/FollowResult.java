// Why this file exists: the possible outcomes of following a short link (see CreateResult).
package com.resume.links.service;

public sealed interface FollowResult {

  record Followed(String linkId, String targetUrl) implements FollowResult {}

  record NotFound() implements FollowResult {}

  // The human-readable reason: the contract's 410 body is plain text.
  record Gone(String message) implements FollowResult {}
}
