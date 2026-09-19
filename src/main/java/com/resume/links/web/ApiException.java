// Why this file exists: lets any code on the request path (the auth interceptor, a controller)
// stop the request with a specific HTTP status and message. The ApiErrors handler turns it into
// the contract's error JSON.
package com.resume.links.web;

import org.springframework.http.HttpStatus;

// JS/TS vs Java: extends RuntimeException, so it is UNCHECKED: no `throws` declarations needed.
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
