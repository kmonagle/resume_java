// Why this file exists: a create request that has PASSED validation, so later code can trust it.
package com.resume.links.service;

import java.time.Instant;

// Nullable fields (title, expiresAt, maxClicks, shortCode) are null when the caller didn't
// provide them.
public record CreateLinkInput(
    String targetUrl, String title, Instant expiresAt, Integer maxClicks, String shortCode) {}
