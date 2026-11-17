package uk.gov.hmcts.cp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code casemanagement.*} messaging settings in {@code application-messaging.yml}: the Service
 * Bus intake and the {@code public.event} subscription.
 */
@ConfigurationProperties(prefix = "casemanagement")
public record MessagingProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue Servicebus servicebus,
        @DefaultValue Publicevents publicevents) {

    /** The master switch for the Service Bus intake. */
    public record Consumer(@DefaultValue("false") boolean enabled) {
    }

    /** The Service Bus queue this service consumes and how it consumes it. */
    public record Servicebus(
            String connectionString,
            String namespace,
            @DefaultValue("casemanagement.requests") String queueName,
            @DefaultValue("2") int maxConcurrentCalls,
            @DefaultValue("5") int maxDeliveryCount,
            @DefaultValue("5m") Duration maxAutoLockRenewDuration,
            @DefaultValue("60s") Duration healthStaleness) {
    }

    /** The shared durable subscription on the platform's {@code public.event} topic. */
    public record Publicevents(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("public.event") String topic,
            @DefaultValue("casemanagement-service.progression") String subscription,
            String selector) {
    }
}
