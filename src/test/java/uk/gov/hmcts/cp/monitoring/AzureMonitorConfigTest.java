package uk.gov.hmcts.cp.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * How {@link AzureMonitorConfig} wires itself for each setting, and that it leaves Spring Boot's own
 * OpenTelemetry SDK (used for tracing) alone. The connection string points at a closed local port:
 * nothing is published in these tests, so nothing is sent.
 */
class AzureMonitorConfigTest {

    static final String FAKE_CONNECTION_STRING = "InstrumentationKey=00000000-0000-0000-0000-000000000000;"
            + "IngestionEndpoint=http://127.0.0.1:9/;LiveEndpoint=http://127.0.0.1:9/";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AzureMonitorConfig.class);

    @Test
    void disabled_by_default_gives_a_noop_service_and_no_exporter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AzureMonitorExporter.class);
            assertThat(context).hasSingleBean(AzureMonitorService.class);
            assertThat(context.getBean(AzureMonitorProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void explicitly_disabled_ignores_a_connection_string() {
        runner.withPropertyValues("casemanagement.azure-monitor.enabled=false",
                        "casemanagement.azure-monitor.connection-string=" + FAKE_CONNECTION_STRING)
                .run(context -> assertThat(context).doesNotHaveBean(AzureMonitorExporter.class));
    }

    @Test
    void enabled_with_a_connection_string_starts_the_exporter() {
        runner.withPropertyValues("casemanagement.azure-monitor.enabled=true",
                        "casemanagement.azure-monitor.connection-string=" + FAKE_CONNECTION_STRING)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AzureMonitorExporter.class);
                    assertThat(context).hasSingleBean(AzureMonitorService.class);
                });
    }

    @Test
    void enabled_without_a_connection_string_fails_start_up() {
        runner.withPropertyValues("casemanagement.azure-monitor.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("CP_CM_AZURE_INSIGHTS_CONNECTION_STRING");
                });
    }

    @Test
    void enabled_with_a_blank_connection_string_fails_start_up() {
        runner.withPropertyValues("casemanagement.azure-monitor.enabled=true",
                        "casemanagement.azure-monitor.connection-string=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void export_interval_defaults_to_a_minute_and_can_be_overridden() {
        runner.run(context -> assertThat(context.getBean(AzureMonitorProperties.class).exportInterval())
                .isEqualTo(Duration.ofSeconds(60)));
        runner.withPropertyValues("casemanagement.azure-monitor.export-interval=5s")
                .run(context -> assertThat(context.getBean(AzureMonitorProperties.class).exportInterval())
                        .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void azure_sdk_does_not_replace_spring_boots_open_telemetry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class))
                .withUserConfiguration(AzureMonitorConfig.class)
                .withPropertyValues("casemanagement.azure-monitor.enabled=true",
                        "casemanagement.azure-monitor.connection-string=" + FAKE_CONNECTION_STRING)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AzureMonitorExporter.class);
                    // Boot still creates exactly one OpenTelemetry and its own meter provider: the
                    // Azure SDK is private to AzureMonitorExporter and never shows up as either type.
                    assertThat(context).hasSingleBean(OpenTelemetry.class);
                    final String bootBean = context.getBeanNamesForType(OpenTelemetry.class)[0];
                    assertThat(context.getBeanFactory().getBeanDefinition(bootBean).getFactoryBeanName())
                            .isEqualTo(OpenTelemetrySdkAutoConfiguration.class.getName());
                    assertThat(context.getBeansOfType(SdkMeterProvider.class)).allSatisfy(
                            (name, provider) -> assertThat(name).doesNotContainIgnoringCase("azure"));
                });
    }

    @Test
    void properties_never_print_the_connection_string() {
        final AzureMonitorProperties properties =
                new AzureMonitorProperties(true, FAKE_CONNECTION_STRING, Duration.ofSeconds(60));

        assertThat(properties.toString()).doesNotContain("InstrumentationKey").contains("connectionString=<set>");
        assertThat(new AzureMonitorProperties(false, null, Duration.ofSeconds(60)).toString())
                .contains("connectionString=<unset>");
    }
}
