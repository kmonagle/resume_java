// Why this file exists: the HTTP layer, and nothing else. It maps requests onto the service and
// service results onto the status codes docs/openapi.yaml promises. Business rules live in
// LinkService; SQL lives in JpaLinkStore.
//
// JS/TS vs Java: this is the counterpart of the route handlers under src/app/api in the Next.js
// repo (and of Express routes). Spring MVC routes are METHODS on a class, mapped by annotations:
// @PostMapping("/links") binds a method to a verb and path, and the method's PARAMETERS are filled
// in by the framework (a @RequestBody is parsed from the JSON, a @PathVariable from the URL, an
// @RequestAttribute from the request).
package com.resume.links.web;

import com.resume.links.service.ClickLogger;
import com.resume.links.service.CreateResult;
import com.resume.links.service.FollowResult;
import com.resume.links.service.LinkService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// @RestController = @Controller + @ResponseBody: every method's return value is written as the
// response body (JSON for objects), rather than being treated as the name of a view template.
@RestController
public class LinkController {

  public static final String IMPLEMENTATION = "Java Spring Boot + JPA";
  public static final String CONTRACT_VERSION = "1";

  private final LinkService service;
  private final ClickLogger clickLogger;

  public LinkController(LinkService service, ClickLogger clickLogger) {
    this.service = service;
    this.clickLogger = clickLogger;
  }

  // ResponseEntity<?> lets a method choose the status, headers and body per case. The body type
  // differs between success (a LinkDto) and failure (an error object), so it is a wildcard.
  @PostMapping("/links")
  public ResponseEntity<?> createLink(
      @RequestBody CreateLinkRequest body, @RequestAttribute(AuthInterceptor.OWNER) String owner) {
    Instant now = service.now();
    CreateLinkValidator.Result validated = CreateLinkValidator.validate(body, now);
    if (!validated.isValid()) {
      return ResponseEntity.badRequest()
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .body(ErrorBodies.Validation.of(validated.errors()));
    }

    CreateResult result = service.create(owner, validated.input());

    // JS/TS vs Java: a `switch` over a SEALED type with PATTERNS. Each case tests the runtime type
    // and binds it (`Created created`). Because CreateResult is sealed, the compiler knows every
    // possible case, so if one were missing this would not compile; there is no `default`.
    return switch (result) {
      case CreateResult.Created created ->
          ResponseEntity.status(HttpStatus.CREATED)
              .header(HttpHeaders.CACHE_CONTROL, "no-store")
              .body(LinkDto.from(created.link(), now));
      case CreateResult.CodeTaken taken -> error(HttpStatus.CONFLICT, "That code is already taken");
      case CreateResult.LimitReached limited ->
          error(HttpStatus.TOO_MANY_REQUESTS, limited.message());
    };
  }

  @GetMapping("/links")
  public List<LinkDto> listLinks(@RequestAttribute(AuthInterceptor.OWNER) String owner) {
    Instant now = service.now();
    // JS/TS vs Java: the STREAM API: `.stream().map(...).toList()` is `.map(...)` on an array, lazy
    // until the terminal `toList()`. `LinkDto::from` is a METHOD REFERENCE (a function value).
    return service.list(owner).stream().map(link -> LinkDto.from(link, now)).toList();
  }

  @PatchMapping("/links/{id}")
  public ResponseEntity<?> setLinkActive(
      @PathVariable String id,
      @RequestBody SetActiveRequest body,
      @RequestAttribute(AuthInterceptor.OWNER) String owner) {
    // `Object isActive` holds whatever the JSON contained; only a real boolean is accepted.
    if (body.isActive() == null) {
      return ResponseEntity.badRequest()
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .body(ErrorBodies.Validation.of(Map.of("isActive", List.of("Required"))));
    }
    if (!(body.isActive() instanceof Boolean active)) {
      return ResponseEntity.badRequest()
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .body(ErrorBodies.invalidBody());
    }

    // 404, not 403, for someone else's link: do not confirm it exists.
    return service
        .setActive(owner, id, active)
        .<ResponseEntity<?>>map(
            link ->
                ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(LinkDto.from(link, service.now())))
        .orElseGet(() -> error(HttpStatus.NOT_FOUND, "Link not found"));
  }

  // Public: anonymous visitors follow short links, so this route has no auth interceptor.
  @GetMapping("/r/{code}")
  public ResponseEntity<String> followLink(
      @PathVariable String code,
      @RequestHeader(value = HttpHeaders.REFERER, required = false) String referrer,
      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
    FollowResult result = service.follow(code);

    return switch (result) {
      case FollowResult.Followed followed -> {
        // Log the click OFF this thread, so analytics never slows the redirect. (The claim above
        // has already been committed: LinkService's transaction ended when follow() returned.)
        clickLogger.record(followed.linkId(), referrer, userAgent);
        // 307 keeps the request method and is never cached by the browser as a permanent move.
        // Never cache: every hit must reach the atomic claim.
        // JS/TS vs Java: inside a switch EXPRESSION, a case with a block `{ ... }` returns its
        // value with `yield` (the block's `return`).
        yield ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
            .header(HttpHeaders.LOCATION, followed.targetUrl())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
      }
      // 410 Gone (not 404): the link existed but is no longer available.
      case FollowResult.Gone gone ->
          ResponseEntity.status(HttpStatus.GONE)
              .contentType(MediaType.TEXT_PLAIN)
              .header(HttpHeaders.CACHE_CONTROL, "no-store")
              .body(gone.message());
      case FollowResult.NotFound notFound ->
          ResponseEntity.status(HttpStatus.NOT_FOUND)
              .contentType(MediaType.TEXT_PLAIN)
              .header(HttpHeaders.CACHE_CONTROL, "no-store")
              .body("Not found");
    };
  }

  @GetMapping("/meta")
  public ResponseEntity<Map<String, String>> meta() {
    // Changes only on redeploy, so briefly cacheable (unlike live link data).
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
        .body(Map.of("implementation", IMPLEMENTATION, "contractVersion", CONTRACT_VERSION));
  }

  private static ResponseEntity<ErrorBodies.Error> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new ErrorBodies.Error(message));
  }
}
