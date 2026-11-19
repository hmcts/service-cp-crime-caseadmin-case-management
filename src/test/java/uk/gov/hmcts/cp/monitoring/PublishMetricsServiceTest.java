package uk.gov.hmcts.cp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.monitoring.PublishMetricsService.EXCEPTIONS_METRIC;
import static uk.gov.hmcts.cp.monitoring.PublishMetricsService.MESSAGE_SETTLEMENTS_METRIC;

import java.io.Serial;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;
import uk.gov.hmcts.cp.inbound.InvalidMessageException;

class PublishMetricsServiceTest {

    private static final String CUSTOM_METRIC = "case_management_custom_failures";

    private final InMemoryAzureMonitor azure = new InMemoryAzureMonitor();
    private final PublishMetricsService metrics = azure.publishMetricsService();

    @PublishToAzureMonitor
    static class AnnotatedException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static class SubclassOfAnnotatedException extends AnnotatedException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    @PublishToAzureMonitor(metricName = CUSTOM_METRIC)
    static class CustomMetricException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static class PlainException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    // ---------- publishExceptionIfEnabled: the opt-in ----------

    @Test
    void annotated_exception_is_published_with_source_type_and_status() {
        final boolean published = metrics.publishExceptionIfEnabled("rest", new AnnotatedException(),
                HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(published).isTrue();
        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsExactly(Map.entry(
                Map.of("source", "rest", "exception_type", "AnnotatedException", "http_status", "500"), 1L));
    }

    @Test
    void exception_without_the_annotation_publishes_nothing() {
        final boolean published = metrics.publishExceptionIfEnabled("rest", new PlainException(), HttpStatus.BAD_REQUEST);

        assertThat(published).isFalse();
        assertThat(azure.metricNames()).isEmpty();
    }

    @Test
    void subclass_inherits_the_opt_in() {
        metrics.publishExceptionIfEnabled("rest", new SubclassOfAnnotatedException(), HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsOnlyKeys(
                Map.of("source", "rest", "exception_type", "SubclassOfAnnotatedException", "http_status", "500"));
    }

    @Test
    void annotation_can_name_its_own_metric() {
        metrics.publishExceptionIfEnabled("servicebus", new CustomMetricException(), null);

        assertThat(azure.metricNames()).containsExactly(CUSTOM_METRIC);
        assertThat(azure.counterValues(CUSTOM_METRIC)).containsOnlyKeys(
                Map.of("source", "servicebus", "exception_type", "CustomMetricException"));
    }

    @Test
    void missing_status_leaves_out_the_http_status_dimension() {
        metrics.publishExceptionIfEnabled("servicebus", new AnnotatedException(), null);

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsOnlyKeys(
                Map.of("source", "servicebus", "exception_type", "AnnotatedException"));
    }

    @Test
    void null_exception_publishes_nothing() {
        assertThat(metrics.publishExceptionIfEnabled("rest", null, HttpStatus.INTERNAL_SERVER_ERROR)).isFalse();
        metrics.publishException("rest", null, null);

        assertThat(azure.metricNames()).isEmpty();
    }

    @Test
    void repeated_exceptions_are_counted() {
        for (int i = 0; i < 3; i++) {
            metrics.publishExceptionIfEnabled("rest", new AnnotatedException(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        assertThat(azure.counterTotal(EXCEPTIONS_METRIC)).isEqualTo(3L);
    }

    // ---------- the service's own opt-ins ----------

    @Test
    void progression_rejection_and_missing_system_user_are_opted_in() {
        metrics.publishExceptionIfEnabled("rest", new ProgressionCommandRejectedException(HttpStatusCode.valueOf(500)),
                HttpStatus.INTERNAL_SERVER_ERROR);
        metrics.publishExceptionIfEnabled("rest", new SystemUserNotConfiguredException(), HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsOnly(
                Map.entry(Map.of("source", "rest", "exception_type", "ProgressionCommandRejectedException",
                        "http_status", "500"), 1L),
                Map.entry(Map.of("source", "rest", "exception_type", "SystemUserNotConfiguredException",
                        "http_status", "500"), 1L));
    }

    @Test
    void client_errors_and_invalid_messages_are_not_opted_in() {
        metrics.publishExceptionIfEnabled("rest", new GroupCaseNotFoundException(java.util.UUID.randomUUID()),
                HttpStatus.NOT_FOUND);
        metrics.publishExceptionIfEnabled("servicebus", new InvalidMessageException("bad"), null);

        assertThat(azure.metricNames()).isEmpty();
    }

    // ---------- explicit publishing ----------

    @Test
    void publish_exception_sends_any_type_unconditionally() {
        metrics.publishException("progression", new PlainException(), HttpStatus.BAD_GATEWAY);

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsExactly(Map.entry(
                Map.of("source", "progression", "exception_type", "PlainException", "http_status", "502"), 1L));
    }

    @Test
    void publish_event_counts_a_business_event() {
        metrics.publishEvent("case_management_group_case_created", "Group cases created", Map.of("source", "rest"));
        metrics.publishEvent("case_management_group_case_created", "Group cases created", Map.of("source", "rest"));

        assertThat(azure.counterValues("case_management_group_case_created"))
                .containsExactly(Map.entry(Map.of("source", "rest"), 2L));
    }

    @Test
    void message_settlement_is_counted_by_outcome_and_reason() {
        metrics.publishMessageSettlement("deadletter", "VALIDATION");
        metrics.publishMessageSettlement("deadletter", "VALIDATION");
        metrics.publishMessageSettlement("complete", null);

        assertThat(azure.counterValues(MESSAGE_SETTLEMENTS_METRIC)).containsOnly(
                Map.entry(Map.of("outcome", "deadletter", "reason", "VALIDATION"), 2L),
                Map.entry(Map.of("outcome", "complete"), 1L));
    }

    @Test
    void annotation_default_metric_is_the_exceptions_metric() throws NoSuchMethodException {
        assertThat(PublishToAzureMonitor.class.getMethod("metricName").getDefaultValue()).isEqualTo(EXCEPTIONS_METRIC);
    }

    // ---------- cause chain and type names ----------

    @Test
    void opted_in_exception_wrapped_in_another_is_published_under_its_own_type() {
        final RuntimeException wrapper = new IllegalStateException("proxy",
                new RuntimeException(new ProgressionCommandRejectedException(HttpStatusCode.valueOf(500))));

        assertThat(metrics.publishExceptionIfEnabled("servicebus", wrapper, null)).isTrue();

        assertThat(azure.counterValues(EXCEPTIONS_METRIC)).containsOnlyKeys(
                Map.of("source", "servicebus", "exception_type", "ProgressionCommandRejectedException"));
    }

    @Test
    void outermost_opted_in_exception_wins_when_several_are_chained() {
        metrics.publishExceptionIfEnabled("rest", new CustomMetricException() {
            @Serial
            private static final long serialVersionUID = 1L;
        }.initCause(new AnnotatedException()), null);

        assertThat(azure.metricNames()).containsExactly(CUSTOM_METRIC);
    }

    @Test
    void wrapped_exception_without_any_opt_in_publishes_nothing() {
        metrics.publishExceptionIfEnabled("rest", new IllegalStateException(new PlainException()), null);

        assertThat(azure.metricNames()).isEmpty();
    }

    @Test
    void cause_chain_search_is_bounded() {
        Throwable chain = new AnnotatedException();
        for (int i = 0; i < 10; i++) {
            chain = new IllegalStateException(chain);
        }

        assertThat(metrics.publishExceptionIfEnabled("rest", chain, null)).isFalse();
    }

    @Test
    void anonymous_opted_in_exception_gets_a_non_empty_type_name() {
        metrics.publishExceptionIfEnabled("rest", new AnnotatedException() {
            @Serial
            private static final long serialVersionUID = 1L;
        }, null);

        assertThat(azure.counterValues(EXCEPTIONS_METRIC).keySet())
                .singleElement()
                .satisfies(dimensions -> assertThat(dimensions.get("exception_type"))
                        .isNotBlank()
                        .startsWith(PublishMetricsServiceTest.class.getName()));
    }
}
