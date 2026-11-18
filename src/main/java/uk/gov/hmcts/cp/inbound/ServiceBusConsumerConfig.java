package uk.gov.hmcts.cp.inbound;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.config.MessagingProperties;
import uk.gov.hmcts.cp.config.ServiceBusHealthIndicator;

/**
 * Wires the Azure Service Bus consumer: a {@link ServiceBusProcessorClient} on the configured queue,
 * PEEK_LOCK with auto-complete disabled, delegating every delivery to
 * {@link CaseManagementMessageListener}, started by {@link ConsumerLifecycleController}.
 *
 * <p>Credentials, as in yot-results-distribution: a connection string locally (the emulator), or a
 * namespace with {@code DefaultAzureCredential} (workload identity) when deployed. Exactly one.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "casemanagement.consumer", name = "enabled", havingValue = "true")
public class ServiceBusConsumerConfig {

    private static final AmqpRetryOptions RECONNECTION = new AmqpRetryOptions()
            .setMode(AmqpRetryMode.EXPONENTIAL)
            .setMaxRetries(5)
            .setDelay(Duration.ofMillis(500))
            .setMaxDelay(Duration.ofSeconds(10))
            .setTryTimeout(Duration.ofSeconds(30));

    @Bean
    public ServiceBusHealthIndicator servicebusHealthIndicator(
            final MessagingProperties properties, final Clock clock) {
        return new ServiceBusHealthIndicator(properties.servicebus().healthStaleness(), clock);
    }

    @Bean
    public CaseManagementMessageListener caseManagementMessageListener(
            final InboundMessageHandler handler, final ServiceBusHealthIndicator health) {
        return new CaseManagementMessageListener(handler, health);
    }

    @Bean(destroyMethod = "close")
    public ServiceBusProcessorClient caseManagementProcessorClient(
            final MessagingProperties properties,
            final CaseManagementMessageListener listener,
            final ServiceBusHealthIndicator health) {
        final MessagingProperties.Servicebus settings = properties.servicebus();
        logSettings(settings);
        return credentialledBuilder(settings)
                .retryOptions(RECONNECTION)
                .processor()
                .queueName(settings.queueName())
                .maxConcurrentCalls(settings.maxConcurrentCalls())
                .maxAutoLockRenewDuration(settings.maxAutoLockRenewDuration())
                .disableAutoComplete()
                .processMessage(delivery -> {
                    health.recordTraffic();
                    listener.onMessage(delivery);
                })
                .processError(error -> reportProcessorError(error, health))
                .buildProcessorClient();
    }

    @Bean
    public ConsumerLifecycleController caseManagementConsumerLifecycle(
            final ServiceBusProcessorClient processor, final MessagingProperties properties,
            final ServiceBusHealthIndicator health) {
        return new ConsumerLifecycleController(processor, properties.servicebus().queueName(), health);
    }

    private static void logSettings(final MessagingProperties.Servicebus settings) {
        log.info("Building the Service Bus consumer. queue={} maxConcurrentCalls={} "
                        + "maxAutoLockRenewDuration={} credential={}",
                settings.queueName(), settings.maxConcurrentCalls(),
                settings.maxAutoLockRenewDuration(),
                hasText(settings.connectionString()) ? "connection-string" : "workload-identity");
    }

    private static ServiceBusClientBuilder credentialledBuilder(final MessagingProperties.Servicebus settings) {
        final boolean connectionString = hasText(settings.connectionString());
        if (connectionString == hasText(settings.namespace())) {
            throw new IllegalStateException("casemanagement.servicebus: set exactly one of "
                    + "connection-string (local emulator) or namespace (workload identity)");
        }
        final ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (connectionString) {
            builder.connectionString(settings.connectionString());
        } else {
            builder.fullyQualifiedNamespace(settings.namespace())
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder;
    }

    private static void reportProcessorError(
            final ServiceBusErrorContext error, final ServiceBusHealthIndicator health) {
        log.error("Service Bus processor error. source={} entityPath={} type={}",
                error.getErrorSource(), error.getEntityPath(),
                error.getException().getClass().getName());
        health.recordError(String.valueOf(error.getErrorSource()), error.getException());
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
