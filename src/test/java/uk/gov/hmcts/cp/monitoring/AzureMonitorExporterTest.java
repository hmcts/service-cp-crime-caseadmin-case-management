package uk.gov.hmcts.cp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AzureMonitorExporterTest {

    @Test
    void sdk_is_restricted_to_metrics_sent_to_azure_monitor() {
        final Map<String, String> properties =
                AzureMonitorExporter.metricsOnlyProperties("service-x", Duration.ofSeconds(30));

        assertThat(properties).containsOnly(
                Map.entry("otel.service.name", "service-x"),
                Map.entry("otel.metrics.exporter", "azure_monitor"),
                Map.entry("otel.traces.exporter", "none"),
                Map.entry("otel.logs.exporter", "none"),
                Map.entry("applicationinsights.live.metrics.enabled", "false"),
                Map.entry("otel.metric.export.interval", "30000"));
    }

    @Test
    void starts_flushes_and_closes_without_reaching_azure() {
        final AzureMonitorExporter exporter = new AzureMonitorExporter(
                AzureMonitorConfigTest.FAKE_CONNECTION_STRING, "service-x", Duration.ofMinutes(10));

        assertThatCode(() -> {
            new AzureMonitorService(exporter.meter(AzureMonitorService.SCOPE_NAME))
                    .publishCounter("case_management_test_events", "Test", Map.of());
            exporter.flush();       // the endpoint is a closed port: the export fails, quietly
            exporter.close();
        }).doesNotThrowAnyException();
    }

    /**
     * Asks the Azure library itself, with exactly the properties the exporter passes, whether Live
     * Metrics would start. Fails if Azure renames the switch or changes how it reads it. Reflection
     * because the method is package-private in the Azure library.
     */
    @Test
    void azure_library_reads_live_metrics_as_disabled() throws ReflectiveOperationException {
        final Method isEnabled = Class.forName("com.azure.monitor.opentelemetry.autoconfigure.LiveMetrics")
                .getDeclaredMethod("isEnabled", ConfigProperties.class);
        isEnabled.setAccessible(true);

        final ConfigProperties exporterProperties = DefaultConfigProperties.createFromMap(
                AzureMonitorExporter.metricsOnlyProperties("service-x", Duration.ofSeconds(30)));
        final ConfigProperties azureDefaults = DefaultConfigProperties.createFromMap(Map.of());

        assertThat(isEnabled.invoke(null, azureDefaults)).as("Azure's default").isEqualTo(true);
        assertThat(isEnabled.invoke(null, exporterProperties)).as("with the exporter's properties").isEqualTo(false);
    }
}
