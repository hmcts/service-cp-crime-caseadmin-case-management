package uk.gov.hmcts.cp.monitoring;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A real OpenTelemetry metrics SDK whose output is read in memory instead of sent to Azure, so
 * tests assert on exactly what Azure Monitor would receive: metric names, values, units and
 * dimensions. Build the code under test on {@link #service()} or {@link #publishMetricsService()}.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class InMemoryAzureMonitor {

    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private final AzureMonitorService service = new AzureMonitorService(provider.get(AzureMonitorService.SCOPE_NAME));

    public AzureMonitorService service() {
        return service;
    }

    public PublishMetricsService publishMetricsService() {
        return new PublishMetricsService(service);
    }

    /** Everything collected so far (cumulative). */
    public Collection<MetricData> collect() {
        return reader.collectAllMetrics();
    }

    /** The names of every metric that has recorded at least one point. */
    public List<String> metricNames() {
        return collect().stream().map(MetricData::getName).sorted().toList();
    }

    /** The one metric with this name, if anything was recorded on it. */
    public Optional<MetricData> metric(final String name) {
        return collect().stream().filter(metric -> metric.getName().equals(name)).findFirst();
    }

    /** The counter points of a metric, keyed by their dimensions; empty when nothing was published. */
    public Map<Map<String, String>, Long> counterValues(final String name) {
        return metric(name)
                .map(metric -> metric.getLongSumData().getPoints().stream()
                        .collect(Collectors.toMap(point -> dimensions(point.getAttributes()), LongPointData::getValue)))
                .orElse(Map.of());
    }

    /** The histogram points of a metric, keyed by their dimensions. */
    public Map<Map<String, String>, HistogramPointData> histogramPoints(final String name) {
        return metric(name)
                .map(metric -> metric.getHistogramData().getPoints().stream()
                        .collect(Collectors.toMap(point -> dimensions(point.getAttributes()), point -> point)))
                .orElse(Map.of());
    }

    /** Sum of every point of a counter, whatever its dimensions. */
    public long counterTotal(final String name) {
        return counterValues(name).values().stream().mapToLong(Long::longValue).sum();
    }

    private static Map<String, String> dimensions(final Attributes attributes) {
        return attributes.asMap().entrySet().stream()
                .collect(Collectors.toMap(entry -> ((AttributeKey<?>) entry.getKey()).getKey(),
                        entry -> String.valueOf(entry.getValue())));
    }
}
