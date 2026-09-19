// Why this file exists: one log line per request (method, path, status, milliseconds), so a log tab
// shows what the service is doing. Spring Boot logs startup but, by default, not requests.
package com.resume.links.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// JS/TS vs Java: a servlet FILTER wraps the whole request/response cycle, like Express middleware
// that calls `next()` and then does more work afterwards. A @Component filter is registered
// automatically. The `finally` guarantees the line is logged even if the request throws.
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // JS/TS vs Java: System.nanoTime() is a monotonic clock for measuring durations (like
    // performance.now()); System.currentTimeMillis() is wall-clock time and can jump. `1_000_000`
    // underscores are digit separators, as in JS.
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      long millis = (System.nanoTime() - start) / 1_000_000;
      log.info(
          "request method={} path={} status={} ms={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          millis);
    }
  }
}
