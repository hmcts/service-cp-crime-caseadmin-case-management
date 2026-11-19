package uk.gov.hmcts.cp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binds the real {@code application.yaml} (which imports {@code application-azure-monitor.yml}), so
 * the environment variables a deployment sets are tested as AKS would set them.
 */
class AzureMonitorPropertiesBindingTest {

    private static final String AKS_VALUE = "InstrumentationKey=aks;IngestionEndpoint=http://127.0.0.1:9/";
    private static final String OVERRIDE_VALUE = "InstrumentationKey=override;IngestionEndpoint=http://127.0.0.1:9/";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AzureMonitorConfig.class);

    @Test
    void defaults_are_off_with_no_connection_string() {
        runner.run(context -> {
            final AzureMonitorProperties properties = context.getBean(AzureMonitorProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.connectionString()).isEmpty();
            assertThat(properties.exportInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(context).doesNotHaveBean(AzureMonitorExporter.class);
        });
    }

    @Test
    void connection_string_comes_from_the_aks_key_vault_variable() {
        runner.withPropertyValues("APPLICATIONINSIGHTS_CONNECTION_STRING=" + AKS_VALUE)
                .run(context -> assertThat(context.getBean(AzureMonitorProperties.class).connectionString())
                        .isEqualTo(AKS_VALUE));
    }

    @Test
    void service_specific_variable_overrides_the_aks_one() {
        runner.withPropertyValues("APPLICATIONINSIGHTS_CONNECTION_STRING=" + AKS_VALUE,
                        "CP_CM_AZURE_INSIGHTS_CONNECTION_STRING=" + OVERRIDE_VALUE)
                .run(context -> assertThat(context.getBean(AzureMonitorProperties.class).connectionString())
                        .isEqualTo(OVERRIDE_VALUE));
    }

    @Test
    void aks_deployment_needs_only_the_switch() {
        runner.withPropertyValues("APPLICATIONINSIGHTS_CONNECTION_STRING=" + AKS_VALUE,
                        "CP_CM_AZURE_MONITOR_ENABLED=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AzureMonitorExporter.class);
                });
    }

    @Test
    void enabled_without_either_variable_names_both_in_the_failure() {
        runner.withPropertyValues("CP_CM_AZURE_MONITOR_ENABLED=true")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("APPLICATIONINSIGHTS_CONNECTION_STRING")
                        .hasMessageContaining("CP_CM_AZURE_INSIGHTS_CONNECTION_STRING"));
    }
}
