package uk.gov.hmcts.cp.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.monitoring.PublishMetricsService.EXCEPTIONS_METRIC;

import io.micrometer.tracing.Tracer;
import java.io.Serial;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;
import uk.gov.hmcts.cp.monitoring.InMemoryAzureMonitor;
import uk.gov.hmcts.cp.monitoring.PublishToAzureMonitor;

/**
 * Every handler in {@link GlobalExceptionHandler} goes through the real opt-in filter: only
 * exceptions annotated with {@link PublishToAzureMonitor} produce a metric, with the status the
 * client was sent.
 */
class GlobalExceptionHandlerMetricsTest {

    private final InMemoryAzureMonitor azure = new InMemoryAzureMonitor();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            new DefaultListableBeanFactory().getBeanProvider(Tracer.class), azure.publishMetricsService());

    @PublishToAzureMonitor
    static class OptedInUnexpectedException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    @Test
    void progression_rejection_is_published_with_status_500() {
        handler.handleProgressionCommandRejected(new ProgressionCommandRejectedException(HttpStatusCode.valueOf(403)));

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsExactly(Map.entry(Map.of("source", "rest",
                "exception_type", "ProgressionCommandRejectedException", "http_status", "500"), 1L));
    }

    @Test
    void missing_system_user_is_published_with_status_500() {
        handler.handleSystemUserNotConfigured(new SystemUserNotConfiguredException());

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsExactly(Map.entry(Map.of("source", "rest",
                "exception_type", "SystemUserNotConfiguredException", "http_status", "500"), 1L));
    }

    @Test
    void opted_in_exception_reaching_the_fallback_handler_is_published() {
        handler.handleGenericException(new OptedInUnexpectedException());

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsOnlyKeys(Map.of("source", "rest",
                "exception_type", "OptedInUnexpectedException", "http_status", "500"));
    }

    @Test
    void exceptions_that_are_not_opted_in_publish_nothing() {
        handler.handleGroupCaseNotFound(new GroupCaseNotFoundException(UUID.randomUUID()));
        handler.handleGroupCaseAlreadyExists(new GroupCaseAlreadyExistsException(UUID.randomUUID()));
        handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad"));
        handler.handleHttpServerError(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                null, null, null));
        handler.handleGenericException(new IllegalStateException("unexpected"));

        assertThat(azure.metricNames()).isEmpty();
    }

    @Test
    void publishing_does_not_change_the_response() {
        final var response = handler.handleProgressionCommandRejected(
                new ProgressionCommandRejectedException(HttpStatusCode.valueOf(500)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Progression did not accept the removal");
        assertThat(response.getBody().getTraceId()).isEqualTo("no-trace");
    }
}
