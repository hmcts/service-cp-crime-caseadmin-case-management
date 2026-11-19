package uk.gov.hmcts.cp.monitoring;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Generic API for publishing custom metrics to Azure Monitor (Application Insights
 * {@code customMetrics}), following cp-ai-rag-service's AzureMonitorService: one OpenTelemetry
 * instrument per metric name, cached, with one attribute per dimension entry so a metric can be
 * split and filtered in Azure.
 *
 * <p>Only what is passed here is sent: nothing is published automatically. Dimension values must
 * come from a small fixed set (types, statuses, outcomes); never put ids or personal data in them,
 * as every distinct value becomes a separate series.
 *
 * <p>Publishing never throws: a telemetry failure is logged and the business flow carries on.
 */
@Slf4j
public class AzureMonitorService {

    /** The OpenTelemetry instrumentation scope of every metric this service publishes. */
    public static final String SCOPE_NAME = "case-management-service-meter";

    private static final String UNIT_DIMENSIONLESS = "1";
    private static final String UNIT_MILLISECONDS = "ms";
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final Meter meter;
    private final Map<String, LongCounter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> histogramCache = new ConcurrentHashMap<>();

    /** Creates the service on the given meter; see {@link AzureMonitorConfig}. */
    public AzureMonitorService(final Meter meter) {
        this.meter = meter;
    }

    /** A service that accepts every call and sends nothing, used when Azure Monitor is disabled. */
    public static AzureMonitorService noop() {
        return new AzureMonitorService(OpenTelemetry.noop().getMeter(SCOPE_NAME));
    }

    /** Adds one to the counter {@code metricName}, e.g. for an event or an exception. */
    public void publishCounter(final String metricName, final String metricDescription,
                               final Map<String, String> dimensions) {
        publishCounter(metricName, metricDescription, 1, dimensions);
    }

    /** Adds {@code amount} (zero or more) to the counter {@code metricName}. */
    public void publishCounter(final String metricName, final String metricDescription, final long amount,
                               final Map<String, String> dimensions) {
        safely(metricName, () -> {
            if (amount < 0) {
                throw new IllegalArgumentException("a counter only goes up; amount=" + amount);
            }
            counterCache.computeIfAbsent(requireName(metricName), name -> meter.counterBuilder(name)
                            .setDescription(describe(metricDescription))
                            .setUnit(UNIT_DIMENSIONLESS)
                            .build())
                    .add(amount, toAttributes(dimensions));
        });
    }

    /**
     * Records a value on the histogram {@code metricName}. Same contract as cp-ai-rag-service's
     * {@code publishHistogramScore}: dimensions with a null value are skipped.
     */
    public void publishHistogramScore(final String metricName, final String metricDescription, final double score,
                                      final Map<String, String> dimensions) {
        safely(metricName, () -> histogram(metricName, metricDescription, UNIT_DIMENSIONLESS)
                .record(score, toAttributes(dimensions)));
    }

    /** Records how long something took, in milliseconds, on the histogram {@code metricName}. */
    public void publishDuration(final String metricName, final String metricDescription, final Duration duration,
                                final Map<String, String> dimensions) {
        safely(metricName, () -> histogram(metricName, metricDescription, UNIT_MILLISECONDS)
                .record(duration.toNanos() / NANOS_PER_MILLI, toAttributes(dimensions)));
    }

    private DoubleHistogram histogram(final String metricName, final String metricDescription, final String unit) {
        return histogramCache.computeIfAbsent(requireName(metricName), name -> meter.histogramBuilder(name)
                .setDescription(describe(metricDescription))
                .setUnit(unit)
                .build());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void safely(final String metricName, final Runnable publish) {
        try {
            publish.run();
            log.debug("Published metric {}", metricName);
        } catch (RuntimeException failure) {
            log.warn("Could not publish metric {}; carrying on without it", metricName, failure);
        }
    }

    private static String requireName(final String metricName) {
        if (metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("metric name is required");
        }
        return metricName;
    }

    private static String describe(final String metricDescription) {
        return metricDescription == null ? "" : metricDescription;
    }

    private static Attributes toAttributes(final Map<String, String> dimensions) {
        final AttributesBuilder builder = Attributes.builder();
        if (dimensions != null) {
            dimensions.forEach((key, value) -> {
                if (key != null && value != null) {
                    builder.put(AttributeKey.stringKey(key), value);
                }
            });
        }
        return builder.build();
    }
}
