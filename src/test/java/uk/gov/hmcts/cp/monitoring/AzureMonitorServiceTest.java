package uk.gov.hmcts.cp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AzureMonitorServiceTest {

    private static final String COUNTER = "case_management_test_events";
    private static final String HISTOGRAM = "case_management_test_score";
    private static final String DURATION = "case_management_test_duration";

    private final InMemoryAzureMonitor azure = new InMemoryAzureMonitor();
    private final AzureMonitorService service = azure.service();

    // ---------- counters ----------

    @Test
    void counter_adds_one_per_call_with_its_dimensions() {
        service.publishCounter(COUNTER, "Test events", Map.of("source", "rest"));
        service.publishCounter(COUNTER, "Test events", Map.of("source", "rest"));

        assertThat(azure.counterValues(COUNTER)).containsExactly(Map.entry(Map.of("source", "rest"), 2L));
    }

    @Test
    void counter_keeps_a_separate_series_per_dimension_value() {
        service.publishCounter(COUNTER, "Test events", Map.of("source", "rest"));
        service.publishCounter(COUNTER, "Test events", Map.of("source", "servicebus"));
        service.publishCounter(COUNTER, "Test events", Map.of("source", "servicebus"));

        assertThat(azure.counterValues(COUNTER)).containsOnly(
                Map.entry(Map.of("source", "rest"), 1L),
                Map.entry(Map.of("source", "servicebus"), 2L));
    }

    @Test
    void counter_adds_the_given_amount() {
        service.publishCounter(COUNTER, "Test events", 5, Map.of());
        service.publishCounter(COUNTER, "Test events", 0, Map.of());

        assertThat(azure.counterTotal(COUNTER)).isEqualTo(5L);
    }

    @Test
    void counter_carries_its_description_unit_and_scope() {
        service.publishCounter(COUNTER, "Test events", Map.of());

        final MetricData metric = azure.metric(COUNTER).orElseThrow();
        assertThat(metric.getDescription()).isEqualTo("Test events");
        assertThat(metric.getUnit()).isEqualTo("1");
        assertThat(metric.getInstrumentationScopeInfo().getName()).isEqualTo(AzureMonitorService.SCOPE_NAME);
        assertThat(metric.getLongSumData().isMonotonic()).isTrue();
    }

    @Test
    void negative_counter_amount_is_rejected_without_throwing() {
        assertThatCode(() -> service.publishCounter(COUNTER, "Test events", -1, Map.of())).doesNotThrowAnyException();

        assertThat(azure.counterTotal(COUNTER)).isZero();
    }

    // ---------- dimensions ----------

    @Test
    void dimensions_with_null_values_are_skipped_as_in_rag_service() {
        final Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("source", "rest");
        dimensions.put("http_status", null);

        service.publishCounter(COUNTER, "Test events", dimensions);

        assertThat(azure.counterValues(COUNTER)).containsOnlyKeys(Map.of("source", "rest"));
    }

    @Test
    void dimension_with_a_null_key_is_skipped() {
        final Map<String, String> dimensions = new HashMap<>();
        dimensions.put(null, "orphan");
        dimensions.put("source", "rest");

        service.publishCounter(COUNTER, "Test events", dimensions);

        assertThat(azure.counterValues(COUNTER)).containsOnlyKeys(Map.of("source", "rest"));
    }

    @Test
    void null_or_empty_dimensions_publish_a_point_without_attributes() {
        service.publishCounter(COUNTER, "Test events", null);
        service.publishCounter(COUNTER, "Test events", Map.of());

        assertThat(azure.counterValues(COUNTER)).containsExactly(Map.entry(Map.of(), 2L));
    }

    // ---------- histograms ----------

    @Test
    void histogram_score_records_count_sum_min_and_max() {
        service.publishHistogramScore(HISTOGRAM, "Scores", 0.25, Map.of("query_type", "a"));
        service.publishHistogramScore(HISTOGRAM, "Scores", 0.75, Map.of("query_type", "a"));

        final HistogramPointData point = azure.histogramPoints(HISTOGRAM).get(Map.of("query_type", "a"));
        assertThat(point.getCount()).isEqualTo(2);
        assertThat(point.getSum()).isEqualTo(1.0);
        assertThat(point.getMin()).isEqualTo(0.25);
        assertThat(point.getMax()).isEqualTo(0.75);
        assertThat(azure.metric(HISTOGRAM).orElseThrow().getUnit()).isEqualTo("1");
    }

    @Test
    void duration_is_recorded_in_milliseconds() {
        service.publishDuration(DURATION, "Latency", Duration.ofMillis(1500), Map.of("operation", "remove_case"));
        service.publishDuration(DURATION, "Latency", Duration.ofNanos(500_000), Map.of("operation", "remove_case"));

        final HistogramPointData point = azure.histogramPoints(DURATION).get(Map.of("operation", "remove_case"));
        assertThat(point.getCount()).isEqualTo(2);
        assertThat(point.getSum()).isEqualTo(1500.5);
        assertThat(azure.metric(DURATION).orElseThrow().getUnit()).isEqualTo("ms");
    }

    @Test
    void null_description_is_accepted() {
        service.publishHistogramScore(HISTOGRAM, null, 1.0, Map.of());

        assertThat(azure.metric(HISTOGRAM).orElseThrow().getDescription()).isEmpty();
    }

    // ---------- caching ----------

    @Test
    void instrument_is_built_once_per_metric_name() {
        final Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
        final AzureMonitorService cached = new AzureMonitorService(meter);

        cached.publishCounter(COUNTER, "Test events", Map.of("a", "1"));
        cached.publishCounter(COUNTER, "Test events", Map.of("a", "2"));
        cached.publishCounter("case_management_other", "Other", Map.of());

        verify(meter, times(1)).counterBuilder(COUNTER);
        verify(meter, times(1)).counterBuilder("case_management_other");
    }

    @Test
    void concurrent_publishing_loses_no_increments() throws InterruptedException {
        final int threads = 8;
        final int perThread = 500;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        service.publishCounter(COUNTER, "Test events", Map.of("source", "rest"));
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(azure.counterTotal(COUNTER)).isEqualTo((long) threads * perThread);
    }

    // ---------- failure isolation ----------

    @Test
    void failure_inside_the_sdk_is_swallowed() {
        final Meter meter = mock(Meter.class);
        when(meter.counterBuilder(anyString())).thenThrow(new IllegalStateException("sdk broken"));
        when(meter.histogramBuilder(anyString())).thenThrow(new IllegalStateException("sdk broken"));
        final AzureMonitorService broken = new AzureMonitorService(meter);

        assertThatCode(() -> {
            broken.publishCounter(COUNTER, "Test events", Map.of());
            broken.publishHistogramScore(HISTOGRAM, "Scores", 1.0, Map.of());
            broken.publishDuration(DURATION, "Latency", Duration.ofMillis(1), Map.of());
        }).doesNotThrowAnyException();
    }

    @Test
    void blank_or_null_metric_name_is_rejected_without_throwing() {
        assertThatCode(() -> {
            service.publishCounter(" ", "Test events", Map.of());
            service.publishCounter(null, "Test events", Map.of());
            service.publishHistogramScore("", "Scores", 1.0, Map.of());
        }).doesNotThrowAnyException();

        assertThat(azure.metricNames()).isEmpty();
    }

    @Test
    void null_duration_is_rejected_without_throwing() {
        assertThatCode(() -> service.publishDuration(DURATION, "Latency", null, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void failure_on_one_metric_does_not_stop_the_next() {
        service.publishCounter(null, "broken", Map.of());
        service.publishCounter(COUNTER, "Test events", Map.of());

        assertThat(azure.metricNames()).isEqualTo(List.of(COUNTER));
    }

    // ---------- no-op ----------

    @Test
    void noop_service_accepts_every_call() {
        final AzureMonitorService noop = AzureMonitorService.noop();

        assertThatCode(() -> {
            noop.publishCounter(COUNTER, "Test events", Map.of("source", "rest"));
            noop.publishCounter(COUNTER, "Test events", 3, Map.of());
            noop.publishHistogramScore(HISTOGRAM, "Scores", 1.0, Map.of());
            noop.publishDuration(DURATION, "Latency", Duration.ofMillis(5), Map.of());
        }).doesNotThrowAnyException();
    }
}
