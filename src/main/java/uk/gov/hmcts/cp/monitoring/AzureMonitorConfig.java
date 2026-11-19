package uk.gov.hmcts.cp.monitoring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Azure Monitor. {@link AzureMonitorService} always exists, so code can publish
 * unconditionally; it is backed by the {@link AzureMonitorExporter} when
 * {@code casemanagement.azure-monitor.enabled=true}, and by a no-op meter otherwise (local runs,
 * tests, environments without Application Insights).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AzureMonitorProperties.class)
public class AzureMonitorConfig {

    /** Starts the exporter. Fails start-up when enabled without a connection string, rather than dropping metrics. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "casemanagement.azure-monitor", name = "enabled", havingValue = "true")
    public AzureMonitorExporter azureMonitorExporter(
            final AzureMonitorProperties properties,
            @Value("${spring.application.name:service-cp-crime-caseadmin-case-management}") final String serviceName) {
        if (properties.connectionString() == null || properties.connectionString().isBlank()) {
            throw new IllegalStateException("casemanagement.azure-monitor.enabled is true but "
                    + "no connection string is set: APPLICATIONINSIGHTS_CONNECTION_STRING (AKS, from Key Vault) "
                    + "or CP_CM_AZURE_INSIGHTS_CONNECTION_STRING");
        }
        return new AzureMonitorExporter(properties.connectionString(), serviceName, properties.exportInterval());
    }

    /** The publishing API; a no-op unless the exporter above exists. */
    @Bean
    public AzureMonitorService azureMonitorService(final ObjectProvider<AzureMonitorExporter> exporter) {
        final AzureMonitorExporter available = exporter.getIfAvailable();
        return available == null
                ? AzureMonitorService.noop()
                : new AzureMonitorService(available.meter(AzureMonitorService.SCOPE_NAME));
    }
}
