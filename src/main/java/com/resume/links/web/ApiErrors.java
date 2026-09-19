// Why this file exists: turns exceptions into the contract's error responses. Spring's defaults
// answer with their own JSON shape (and 500s for unexpected errors); the contract wants
// {"error": ...} and, for validation, 400 with {"error", "fieldErrors"}.
package com.resume.links.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// JS/TS vs Java: @RestControllerAdvice is a global exception handler: each @ExceptionHandler
// method is chosen by the exception's TYPE (the closest match wins), and its return value is
// written as the response, like an Express error-handling middleware with a switch on the error.
@RestControllerAdvice
public class ApiErrors {

  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorBodies.Error> handleApi(ApiException e) {
    return json(e.status(), new ErrorBodies.Error(e.getMessage()));
  }

  // A body that isn't JSON, or has values of the wrong type, or is missing: the CLIENT's mistake,
  // so a 400 (never a 500), in the contract's validation shape.
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<ErrorBodies.Validation> handleBadBody(Exception e) {
    return json(HttpStatus.BAD_REQUEST, ErrorBodies.invalidBody());
  }

  // Everything else. Spring's own exceptions (405 wrong method, 404 no such route...) implement
  // ErrorResponse and already know their status: keep it, but in the contract's error shape. (The
  // handler parameter has to be a Throwable, so this is an instanceof check, not a separate
  // handler.) Anything else is a bug or an outage: the real cause goes to the log, and the client
  // is told nothing internal.
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBodies.Error> handleUnexpected(Exception e) {
    if (e instanceof ErrorResponse framework) {
      return json(framework.getStatusCode(), new ErrorBodies.Error(framework.getBody().getTitle()));
    }
    log.error("Unhandled exception", e);
    return json(HttpStatus.INTERNAL_SERVER_ERROR, new ErrorBodies.Error("Internal error"));
  }

  private static <T> ResponseEntity<T> json(HttpStatusCode status, T body) {
    return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
  }
}
