package uk.gov.hmcts.cp.config;

import jakarta.jms.ConnectionFactory;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.MessageListenerContainer;
import uk.gov.hmcts.cp.adapter.publicevents.ProgressionEventListener;
import uk.gov.hmcts.cp.services.ProgressionEventSink;

/**
 * The {@code public.event} subscription: a dedicated listener container factory for a SHARED DURABLE
 * topic subscription, the listener, and its health. Same settings as yot-results-distribution's
 * PublicEventsConfig.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "casemanagement.publicevents", name = "enabled", havingValue = "true")
public class PublicEventsConfig {

    public static final String LISTENER_CONTAINER_FACTORY = "publicEventListenerContainerFactory";

    /** One consumer per pod; replicas share the subscription, so the broker spreads events across pods. */
    private static final String ONE_PER_POD = "1";

    @Bean(LISTENER_CONTAINER_FACTORY)
    public DefaultJmsListenerContainerFactory publicEventListenerContainerFactory(
            final ConnectionFactory connectionFactory, final JmsProperties jms) {
        final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        // Unwrapped from Boot's caching factory: a durable consumer must not be cached.
        factory.setConnectionFactory(ConnectionFactoryUnwrapper.unwrap(connectionFactory));
        factory.setPubSubDomain(jms.isPubSubDomain());
        factory.setSubscriptionDurable(jms.isSubscriptionDurable());
        // Shared: keyed by subscription name alone, no client id, so every replica can attach.
        factory.setSubscriptionShared(true);
        factory.setConcurrency(ONE_PER_POD);
        // Transacted: a listener throw rolls back and the broker redelivers.
        factory.setSessionTransacted(true);
        factory.setErrorHandler(PublicEventsConfig::notApplied);
        return factory;
    }

    private static void notApplied(final Throwable notApplied) {
        log.error("A public event was not applied, so the transacted session is rolled back and the "
                + "broker offers the message again. cause={}", notApplied.getClass().getName());
    }

    @Bean
    public PublicEventsHealthIndicator publicEventsHealthIndicator(
            final ObjectProvider<JmsListenerEndpointRegistry> containers, final Clock clock) {
        return new PublicEventsHealthIndicator(() -> subscriptionRunning(containers), clock);
    }

    @Bean
    public ProgressionEventListener progressionEventListener(
            final ProgressionEventSink sink, final PublicEventsHealthIndicator health) {
        return new ProgressionEventListener(sink, health);
    }

    private static boolean subscriptionRunning(
            final ObjectProvider<JmsListenerEndpointRegistry> containers) {
        final JmsListenerEndpointRegistry registry = containers.getIfAvailable();
        return registry != null
                && !registry.getListenerContainers().isEmpty()
                && registry.getListenerContainers().stream().allMatch(MessageListenerContainer::isRunning);
    }
}
