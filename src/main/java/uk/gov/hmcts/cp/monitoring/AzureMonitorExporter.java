package uk.gov.hmcts.cp.monitoring;

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the OpenTelemetry SDK that ships this service's custom metrics to Application Insights,
 * the same SDK set-up as cp-ai-rag-service's AzureMonitorService.
 *
 * <p>Two deliberate differences from that service, because this one runs in Spring Boot:
 * <ul>
 *   <li>the SDK is never registered as {@code GlobalOpenTelemetry}, and is never exposed as an
 *       {@code OpenTelemetry} or {@code SdkMeterProvider} bean. Spring Boot creates its own SDK (for
 *       tracing) only when no {@code OpenTelemetry} bean exists, and adopts any
 *       {@code SdkMeterProvider} bean, so exposing this one would silently replace or alter that;</li>
 *   <li>it exports metrics only. Azure's auto-configuration turns on trace and log export and Live
 *       Metrics as well; those are switched off here, so only what code publishes through
 *       {@link AzureMonitorService} reaches Application Insights.</li>
 * </ul>
 *
 * <p>The Azure SDK also sends its own health telemetry ("statsbeat": SDK version, export success
 * counts) to a Microsoft-owned endpoint, not to this service's Application Insights. It carries no
 * application data and the exporter has no supported switch for it.
 */
@Slf4j
public final class AzureMonitorExporter implements AutoCloseable {

    private static final long FLUSH_TIMEOUT_SECONDS = 10;

    private final OpenTelemetrySdk sdk;

    /** Builds the SDK and starts the periodic export to the resource in the connection string. */
    public AzureMonitorExporter(final String connectionString, final String serviceName,
                                final Duration exportInterval) {
        final AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook();                       // Spring closes it, see close()
        AzureMonitorAutoConfigure.customize(builder, connectionString);
        // Entra ID (managed identity) is NOT IN USE YET: ingestion is authorised by the key in the
        // connection string. To switch, pass AzureMonitorAutoConfigureOptions here instead, with the
        // same connection string (it still carries the endpoint) plus a DefaultAzureCredential, and
        // grant the pod's workload identity "Monitoring Metrics Publisher" on the resource. The exact
        // code and the Azure steps are in docs/AzureMonitor.md, "Moving to Entra ID".
        // A properties customizer runs after every properties supplier (Azure's included) and after
        // OTEL_* environment variables, so these always win.
        builder.addPropertiesCustomizer(config -> metricsOnlyProperties(serviceName, exportInterval));
        this.sdk = builder.build().getOpenTelemetrySdk();
        log.info("Azure Monitor metrics exporter started. service={} exportInterval={}", serviceName, exportInterval);
    }

    /** The OpenTelemetry properties that restrict this SDK to exporting metrics to Azure Monitor. */
    /* default */ static Map<String, String> metricsOnlyProperties(final String serviceName, final Duration exportInterval) {
        return Map.of(
                "otel.service.name", serviceName,             // becomes cloud_RoleName in Application Insights
                "otel.metrics.exporter", "azure_monitor",
                "otel.traces.exporter", "none",
                "otel.logs.exporter", "none",
                "applicationinsights.live.metrics.enabled", "false",   // on by default: pings LiveEndpoint
                "otel.metric.export.interval", String.valueOf(exportInterval.toMillis()));
    }

    /** A meter whose instruments are exported to Azure Monitor. */
    public Meter meter(final String scopeName) {
        return sdk.getMeter(scopeName);
    }

    /** Exports everything collected so far, without waiting for the next interval. */
    public void flush() {
        sdk.getSdkMeterProvider().forceFlush().join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Sends what is still pending, then stops the exporter. Called by Spring on shutdown. */
    @Override
    public void close() {
        log.info("Stopping the Azure Monitor metrics exporter");
        flush();
        sdk.close();
    }
}
