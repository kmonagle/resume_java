// Why this file exists: authentication for the API routes. It rejects requests without the shared
// bearer token (401) or without a usable X-Owner-Id (400), and hands the owner id to the
// controller.
//
// JS/TS vs Java: this is MIDDLEWARE scoped to a set of routes, the Spring MVC equivalent of an
// Express `router.use(auth)`. A HandlerInterceptor's preHandle runs BEFORE the controller method
// is invoked, which means before the request body is read and parsed: so an unauthenticated caller
// never learns anything about the body's validity. The order of the checks matters too: the token
// is checked BEFORE the owner.
package com.resume.links.web;

import com.resume.links.config.LinkProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

  /** The request attribute the controller reads the owner id from. */
  public static final String OWNER = "owner";

  private static final String BEARER = "Bearer ";
  private static final Pattern OWNER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  // JS/TS vs Java: `final` on a field means it is assigned exactly once (in the constructor) and
  // never again: Java's `readonly`. Fields with no `final` are mutable.
  private final byte[] expectedHash;

  public AuthInterceptor(LinkProperties properties) {
    // Only the HASH of the token is kept in memory.
    this.expectedHash = sha256(properties.token());
  }

  @Override
  // JS/TS vs Java: HttpServletRequest/Response come from the SERVLET API, Java's long-standing
  // web standard that Tomcat (the embedded server) implements: the counterpart of Node's `req`
  // and `res`, with getters for everything. Spring wraps them so most code never touches them.
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    // Live, per-owner data must never be cached by browsers, proxies or the BFF.
    response.setHeader("Cache-Control", "no-store");

    String header = request.getHeader("Authorization");
    if (header == null
        || !header.startsWith(BEARER)
        || !tokenMatches(header.substring(BEARER.length()))) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
    }

    String owner = request.getHeader("X-Owner-Id");
    if (owner == null || !OWNER_ID.matcher(owner).matches()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "X-Owner-Id header is required (1-64 chars: letters, numbers, - and _)");
    }

    // Request attributes are a per-request map, the Java twin of setting `req.user = ...` in
    // Express.
    request.setAttribute(OWNER, owner);
    return true; // true = carry on to the controller
  }

  // Compare SHA-256 hashes with MessageDigest.isEqual, which takes the same time whatever the
  // input. Hashing makes both sides always 32 bytes (so length leaks nothing), and the
  // constant-time compare stops an attacker guessing the token byte by byte from response timing,
  // which `Arrays.equals` (or a string ==) would allow.
  private boolean tokenMatches(String presented) {
    return MessageDigest.isEqual(sha256(presented), expectedHash);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      // JS/TS vs Java: getInstance declares a CHECKED exception, so the compiler forces us to
      // handle it even though SHA-256 is guaranteed to exist on every JVM. Wrapping it in an
      // unchecked one is the usual move.
      throw new IllegalStateException(e);
    }
  }
}
