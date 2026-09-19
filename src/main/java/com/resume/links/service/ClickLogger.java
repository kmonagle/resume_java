// Why this file exists: records click events OFF the request thread, so analytics never slows the
// redirect the visitor is waiting for.
package com.resume.links.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// JS/TS vs Java: @Async makes Spring run the method on another thread and return to the caller
// immediately: the counterpart of Next's `after()`, Go's goroutine and C#'s OnCompleted. It works
// through the same PROXY mechanism as @Transactional, which is why this lives in its OWN class:
// calling an @Async method from within the same class would run it synchronously.
//
// With virtual threads enabled (application.properties) the task runs on a virtual thread. The
// work happens in LinkService.recordClick, whose own transaction opens its own database
// connection; nothing is shared with the request that triggered it. A failed click log is
// acceptable to lose (nobody is waiting on it), so it is logged and dropped.
@Component
public class ClickLogger {

  private static final Logger log = LoggerFactory.getLogger(ClickLogger.class);

  private final LinkService service;

  public ClickLogger(LinkService service) {
    this.service = service;
  }

  @Async
  public void record(String linkId, String referrer, String userAgent) {
    try {
      service.recordClick(linkId, referrer, userAgent);
    } catch (RuntimeException e) {
      // JS/TS vs Java: `log.error(message, exception)` logs the stack trace too. Loggers come
      // from SLF4J, the standard logging facade, and `{}` placeholders are filled lazily.
      log.error("Recording click event failed", e);
    }
  }
}
