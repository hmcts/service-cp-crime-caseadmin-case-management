package uk.gov.hmcts.cp.controllers;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

/**
 * Maps exceptions to the ErrorResponse defined in the OpenAPI spec: 400, 404 and 409, plus a
 * 500 fallback so the client never sees a body that is not an ErrorResponse. Messages carry
 * field names and ids only, never request values, so personal data is not echoed back.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

    private static final String BAD_REQUEST = "Bad Request";

    private final ObjectProvider<Tracer> tracer;

    /** Constructs the handler; the tracer is optional so it also works without tracing. */
    public GlobalExceptionHandler(final ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** Handles a groupId that already exists and returns a 409 ErrorResponse. */
    @ExceptionHandler(GroupCaseAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleGroupCaseAlreadyExists(final GroupCaseAlreadyExistsException exception) {
        return error(HttpStatus.CONFLICT, "Conflict", exception.getMessage());
    }

    /** Handles a referenced group case that does not exist and returns a 404 ErrorResponse. */
    @ExceptionHandler(GroupCaseNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGroupCaseNotFound(final GroupCaseNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "Not Found", exception.getMessage());
    }

    /** Handles bean validation failures on request bodies and returns a 400 ErrorResponse. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(final MethodArgumentNotValidException exception) {
        final String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, detail.isEmpty() ? "Validation failed" : detail);
    }

    /** Handles malformed request bodies and returns a 400 ErrorResponse. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(final HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Malformed request body");
    }

    /** Handles a missing required header, such as CJSCPPUID, and returns a 400 ErrorResponse. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(final MissingRequestHeaderException exception) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Required header " + exception.getHeaderName() + " is missing");
    }

    /** Handles any uncaught exception and returns a 500 ErrorResponse without internal details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(final Exception exception) {
        log.error("Unhandled exception", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> error(final HttpStatus status, final String error, final String message) {
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse()
                        .error(error)
                        .message(message)
                        .traceId(resolveTraceId())
                        .timestamp(Instant.now()));
    }

    private String resolveTraceId() {
        final Tracer current = tracer.getIfAvailable();
        final Span span = current == null ? null : current.currentSpan();
        return span == null ? "no-trace" : span.context().traceId();
    }
}
