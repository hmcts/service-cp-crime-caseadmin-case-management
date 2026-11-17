package uk.gov.hmcts.cp.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.gov.hmcts.cp.Application;

/**
 * Boots the whole application, as deployed, against a real Postgres and the Service Bus emulator,
 * with the consumer switched on (as yot-results-distribution's ServiceTestSupport does). The e2e
 * suites drive it only through the queue and read it only through the queue and the health endpoint.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class ServiceTestSupport {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("casemanagement")
            .withUsername("postgres")
            .withPassword("postgres");

    private ServiceTestSupport() {
    }

    /**
     * Starts the application with the consumer on; {@code overrides} win over the defaults, and
     * {@code extraSources} (plain classes with @Bean methods, NOT @Configuration, so the application's
     * component scan never picks them up for another suite) add or replace beans.
     */
    public static ConfigurableApplicationContext start(
            final Map<String, String> overrides, final Class<?>... extraSources) {
        final Map<String, String> properties = new LinkedHashMap<>(defaults());
        properties.putAll(overrides);
        final Class<?>[] sources = new Class<?>[extraSources.length + 1];
        sources[0] = Application.class;
        System.arraycopy(extraSources, 0, sources, 1, extraSources.length);
        return new SpringApplicationBuilder(sources)
                .web(WebApplicationType.NONE)
                .run(properties.entrySet().stream()
                        .map(setting -> "--" + setting.getKey() + '=' + setting.getValue())
                        .toArray(String[]::new));
    }

    private static synchronized Map<String, String> defaults() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        final Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("casemanagement.consumer.enabled", "true");
        properties.put("casemanagement.servicebus.connection-string",
                ServiceBusEmulatorTestSupport.connectionString());
        properties.put("casemanagement.servicebus.queue-name", ServiceBusEmulatorTestSupport.QUEUE_NAME);
        return properties;
    }

    /** The status of the {@code servicebusHealthIndicator} health component. */
    public static Status serviceBusHealth(final ConfigurableApplicationContext context) {
        final CompositeHealthDescriptor health =
                (CompositeHealthDescriptor) context.getBean(HealthEndpoint.class).health();
        final HealthDescriptor component = health.getComponents().get("servicebus");
        return component == null ? Status.UNKNOWN : component.getStatus();
    }
}
