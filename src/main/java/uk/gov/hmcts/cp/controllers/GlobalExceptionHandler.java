package uk.gov.hmcts.cp.controllers;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;
import uk.gov.hmcts.cp.monitoring.PublishMetricsService;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

/**
 * Maps exceptions to the ErrorResponse defined in the OpenAPI spec: 400, 404, 409 and 500, plus a
 * 500 fallback so the client never sees a body that is not an ErrorResponse. Messages carry
 * field names and ids only, never request values, so personal data is not echoed back.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

    private static final String BAD_REQUEST = "Bad Request";
    private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

    /** The {@code source} dimension of exceptions this handler publishes to Azure Monitor. */
    private static final String METRIC_SOURCE = "rest";

    private final ObjectProvider<Tracer> tracer;
    private final PublishMetricsService publishMetricsService;

    /** Constructs the handler; the tracer is optional so it also works without tracing. */
    public GlobalExceptionHandler(final ObjectProvider<Tracer> tracer, final PublishMetricsService publishMetricsService) {
        this.tracer = tracer;
        this.publishMetricsService = publishMetricsService;
    }

    /** Handles a groupId that already exists and returns a 409 ErrorResponse. */
    @ExceptionHandler(GroupCaseAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleGroupCaseAlreadyExists(final GroupCaseAlreadyExistsException exception) {
        return error(exception, HttpStatus.CONFLICT, "Conflict", exception.getMessage());
    }

    /** Handles a referenced group case that does not exist and returns a 404 ErrorResponse. */
    @ExceptionHandler(GroupCaseNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGroupCaseNotFound(final GroupCaseNotFoundException exception) {
        return error(exception, HttpStatus.NOT_FOUND, "Not Found", exception.getMessage());
    }

    /**
     * Handles a command Progression did not accept (or could not receive) and returns a 500
     * ErrorResponse. Progression's own status is logged but not passed through, so a rejection
     * of the system user is not reported to the caller as their own 403.
     */
    @ExceptionHandler(ProgressionCommandRejectedException.class)
    public ResponseEntity<ErrorResponse> handleProgressionCommandRejected(
            final ProgressionCommandRejectedException exception) {
        log.error("Progression command not accepted: {}", exception.getMessage(), exception);
        return error(exception, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR, "Progression did not accept the removal");
    }

    /** Handles a missing system user id configuration and returns a 500 ErrorResponse. */
    @ExceptionHandler(SystemUserNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleSystemUserNotConfigured(final SystemUserNotConfiguredException exception) {
        log.error("Cannot call Progression: {}", exception.getMessage());
        return error(exception, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    /** Handles bean validation failures on request bodies and returns a 400 ErrorResponse. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(final MethodArgumentNotValidException exception) {
        final String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(exception, HttpStatus.BAD_REQUEST, BAD_REQUEST, detail.isEmpty() ? "Validation failed" : detail);
    }

    /** Handles a query/path parameter that fails its bean-validation constraint and returns a 400 ErrorResponse. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(final ConstraintViolationException exception) {
        final String detail = exception.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(exception, HttpStatus.BAD_REQUEST, BAD_REQUEST, detail.isEmpty() ? "Validation failed" : detail);
    }

    /** Handles malformed request bodies and returns a 400 ErrorResponse. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(final HttpMessageNotReadableException exception) {
        return error(exception, HttpStatus.BAD_REQUEST, BAD_REQUEST, "Malformed request body");
    }

    /** Handles a missing required header, such as CJSCPPUID, and returns a 400 ErrorResponse. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(final MissingRequestHeaderException exception) {
        return error(exception, HttpStatus.BAD_REQUEST, BAD_REQUEST, "Required header " + exception.getHeaderName() + " is missing");
    }

    /** Handles a missing required query parameter, such as courtCentreId, and returns a 400 ErrorResponse. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
            final MissingServletRequestParameterException exception) {
        return error(exception, HttpStatus.BAD_REQUEST, BAD_REQUEST,
                "Required parameter " + exception.getParameterName() + " is missing");
    }

    /** Handles a query parameter that cannot be converted to its declared type and returns a 400 ErrorResponse. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
            final MethodArgumentTypeMismatchException exception) {
        return error(exception, HttpStatus.BAD_REQUEST, BAD_REQUEST, "Parameter " + exception.getName() + " is invalid");
    }

    /** Handles an explicit business-error throw and passes its status straight through. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(final ResponseStatusException exception) {
        final HttpStatusCode status = exception.getStatusCode();
        if (status.is4xxClientError()) {
            log.warn("Business error: {}", exception.getReason());
        } else {
            log.error("Business error: {}", exception.getReason(), exception);
        }
        return error(exception, status, HttpStatus.valueOf(status.value()).getReasonPhrase(), exception.getReason());
    }

    /** Handles a 4xx from an upstream RestClient call (e.g. Progression) and passes the status through. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpClientError(final HttpClientErrorException exception) {
        log.warn("Upstream client error: {}", exception.getStatusCode());
        return error(exception, exception.getStatusCode(), exception.getStatusText(), "Upstream request failed");
    }

    /** Handles a 5xx from an upstream RestClient call (e.g. Progression) and passes the status through. */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpServerError(final HttpServerErrorException exception) {
        log.error("Upstream server error: {}", exception.getStatusCode(), exception);
        return error(exception, exception.getStatusCode(), exception.getStatusText(), "Upstream request failed");
    }

    /** Handles any uncaught exception and returns a 500 ErrorResponse without internal details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(final Exception exception) {
        log.error("Unhandled exception", exception);
        return error(exception, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    /**
     * Builds the ErrorResponse. Every handler goes through here, so this is where an exception whose
     * type is annotated with {@code @PublishToAzureMonitor} is published; other exceptions are not.
     */
    private ResponseEntity<ErrorResponse> error(final Exception exception, final HttpStatusCode status,
                                                final String error, final String message) {
        publishMetricsService.publishExceptionIfEnabled(METRIC_SOURCE, exception, status);
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
