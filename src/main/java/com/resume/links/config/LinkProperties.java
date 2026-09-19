// Why this file exists: the service's own settings as a typed, validated object, so a missing or
// malformed setting stops the process at startup with a clear message instead of failing on some
// later request.
package com.resume.links.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// JS/TS vs Java: @ConfigurationProperties binds settings onto a class by name: the prefix
// "link.backend" plus the record component `token` reads `link.backend.token`, and Spring's
// relaxed binding also accepts the environment variable LINK_BACKEND_TOKEN. @Validated makes the
// constraint annotations (Bean Validation) run at startup. This is the counterpart of
// src/server/env.ts (zod) in the Next.js repo.
//
// The token is the shared secret the Next.js BFF sends as a bearer token. Render's free tier has
// no private networking, so this service is publicly reachable; without the token anyone could
// call it with any X-Owner-Id.
@Validated
@ConfigurationProperties(prefix = "link.backend")
public record LinkProperties(
    @NotBlank(message = "LINK_BACKEND_TOKEN is required")
        @Size(min = 16, message = "LINK_BACKEND_TOKEN must be at least 16 characters")
        String token) {}
