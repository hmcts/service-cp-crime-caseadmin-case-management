package uk.gov.hmcts.cp.monitoring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code casemanagement.azure-monitor.*} settings in {@code application-azure-monitor.yml}.
 *
 * @param enabled          master switch; when false every publish is a no-op and nothing leaves the service
 * @param connectionString the Application Insights connection string (required when enabled)
 * @param exportInterval   how often collected metrics are sent to Azure Monitor
 */
@ConfigurationProperties(prefix = "casemanagement.azure-monitor")
public record AzureMonitorProperties(
        @DefaultValue("false") boolean enabled,
        String connectionString,
        @DefaultValue("60s") Duration exportInterval) {

    /** Hides the connection string, which carries the instrumentation key, from logs and actuator output. */
    @Override
    public String toString() {
        return "AzureMonitorProperties[enabled=" + enabled + ", connectionString="
                + (connectionString == null || connectionString.isBlank() ? "<unset>" : "<set>")
                + ", exportInterval=" + exportInterval + "]";
    }
}
