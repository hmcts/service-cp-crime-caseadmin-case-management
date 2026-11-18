package uk.gov.hmcts.cp.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.adapter.publicevents.ProgressionEventListener.GROUP_PROSECUTION_CASES_CREATED;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.cp.config.PublicEventsConfig;
import uk.gov.hmcts.cp.config.PublicEventsHealthIndicator;
import uk.gov.hmcts.cp.services.ProgressionEventSink;

/**
 * The real subscription against an in-process Artemis broker, as yot-results-distribution's
 * DocumentEventListenerIT tests its own: events are published to the {@code public.event} TOPIC with a
 * CPPNAME property, exactly as the CQRS framework publishes them, and must arrive through the
 * broker-side selector, the SHARED DURABLE subscription and the transacted listener.
 */
@SpringBootTest(
        classes = PublicEventsSubscriptionIT.Slice.class,
        properties = {
            "spring.config.import=classpath:application-messaging.yml",
            "spring.artemis.mode=embedded",
            "spring.artemis.embedded.topics=public.event",
            "casemanagement.publicevents.enabled=true"
        })
@DisplayName("the durable subscription to public.event")
class PublicEventsSubscriptionIT {

    private static final String TOPIC = "public.event";
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(10);
    private static final UUID GROUP_ID = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");

    @ImportAutoConfiguration({ArtemisAutoConfiguration.class, JmsAutoConfiguration.class})
    @Import(PublicEventsConfig.class)
    static class Slice {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @MockitoBean
    private ProgressionEventSink sink;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private PublicEventsHealthIndicator health;

    @Autowired
    private JmsListenerEndpointRegistry listeners;

    @Autowired
    private EmbeddedActiveMQ broker;

    @Autowired
    private DefaultJmsListenerContainerFactory publicEventListenerContainerFactory;

    @Value("${casemanagement.publicevents.subscription}")
    private String subscription;

    @Value("${casemanagement.publicevents.selector}")
    private String selector;

    @BeforeEach
    void listening() {
        startTheListener();
        await().atMost(DELIVERED_WITHIN).until(() -> "UP".equals(health.health().getStatus().getCode()));
    }

    private void startTheListener() {
        listeners.start();
        await().atMost(DELIVERED_WITHIN).until(() -> consumersOnTheSubscription() == 1);
    }

    private void stopTheListener() {
        listeners.stop();
        await().atMost(DELIVERED_WITHIN).until(() -> consumersOnTheSubscription() == 0);
    }

    private void publish(final String cppName, final String body) {
        jmsTemplate.convertAndSend(TOPIC, body, message -> {
            message.setStringProperty("CPPNAME", cppName);
            return message;
        });
    }

    private static String event(final UUID groupId) {
        return ProgressionEventListenerTest.envelope(GROUP_PROSECUTION_CASES_CREATED, groupId.toString());
    }

    private ActiveMQServer server() {
        return broker.getActiveMQServer();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private List<String> subscriptionsOnTheTopic() {
        try {
            return server().bindingQuery(SimpleString.of(TOPIC)).getQueueNames().stream()
                    .map(SimpleString::toString).toList();
        } catch (Exception unanswered) {
            throw new IllegalStateException("the broker did not answer a binding query", unanswered);
        }
    }

    private long consumersOnTheSubscription() {
        return subscriptionsOnTheTopic().stream()
                .map(name -> server().locateQueue(SimpleString.of(name)))
                .mapToLong(Queue::getConsumerCount)
                .sum();
    }

    @Test
    @DisplayName("hands a published event to the sink, whole, through the durable subscription")
    void published_event_reaches_the_sink_through_the_durable_subscription() {
        publish(GROUP_PROSECUTION_CASES_CREATED, event(GROUP_ID));

        verify(sink, timeout(DELIVERED_WITHIN.toMillis()))
                .groupProsecutionCasesCreated(eq(GROUP_ID), contains("\"_metadata\""));
        assertThat(health.health().getDetails().get("lastDeliveryAt")).isNotEqualTo("none");
    }

    @Test
    @DisplayName("delivers an event published while the listener was stopped, once it restarts")
    void an_event_published_while_the_listener_is_stopped_should_be_delivered_on_restart() {
        final UUID groupId = UUID.randomUUID();
        stopTheListener();

        publish(GROUP_PROSECUTION_CASES_CREATED, event(groupId));
        verify(sink, after(1_000).never()).groupProsecutionCasesCreated(any(), any());
        startTheListener();

        verify(sink, timeout(DELIVERED_WITHIN.toMillis())).groupProsecutionCasesCreated(eq(groupId), any());
    }

    @Test
    @DisplayName("is never handed an event carrying another context's CPPNAME")
    void the_selector_should_keep_another_contexts_event_off_the_subscription() {
        final UUID excluded = UUID.randomUUID();
        final UUID ours = UUID.randomUUID();

        publish("public.progression.prosecution-case-created", event(excluded));
        publish(GROUP_PROSECUTION_CASES_CREATED, event(ours));

        verify(sink, timeout(DELIVERED_WITHIN.toMillis())).groupProsecutionCasesCreated(eq(ours), any());
        verify(sink, never()).groupProsecutionCasesCreated(eq(excluded), any());
    }

    @Test
    @DisplayName("hands two announcements of one group to the sink twice: the sink is the one to be idempotent")
    void two_events_for_one_group_should_both_be_delivered() {
        final UUID groupId = UUID.randomUUID();

        publish(GROUP_PROSECUTION_CASES_CREATED, event(groupId));
        publish(GROUP_PROSECUTION_CASES_CREATED, event(groupId));

        verify(sink, timeout(DELIVERED_WITHIN.toMillis()).times(2)).groupProsecutionCasesCreated(eq(groupId), any());
    }

    @Test
    @DisplayName("offers the event again when the sink could not apply it")
    void an_event_the_sink_could_not_apply_should_be_offered_again() {
        final UUID groupId = UUID.randomUUID();
        doThrow(new IllegalStateException("the database is unavailable"))
                .doNothing()
                .when(sink).groupProsecutionCasesCreated(eq(groupId), any());

        publish(GROUP_PROSECUTION_CASES_CREATED, event(groupId));

        verify(sink, timeout(DELIVERED_WITHIN.toMillis()).times(2)).groupProsecutionCasesCreated(eq(groupId), any());
    }

    @Nested
    @DisplayName("with the deployment scaled past one pod")
    class ScaledPastOnePod {

        private static final int PODS = 2;
        private static final int EVENTS = 8;

        private final List<DefaultMessageListenerContainer> pods = new ArrayList<>();
        private final Collection<String> delivered = new ConcurrentLinkedQueue<>();

        @BeforeEach
        void standTheContextsOwnContainerDown() {
            doNothing().when(sink).groupProsecutionCasesCreated(any(), any());
            stopTheListener();
        }

        @AfterEach
        void detachThePods() {
            pods.forEach(DefaultMessageListenerContainer::destroy);
            pods.clear();
        }

        @Test
        @DisplayName("attaches every pod to the one subscription and hands each event to one of them")
        void two_pods_on_one_subscription_should_both_attach_and_split_the_events_between_them() {
            for (int pod = 0; pod < PODS; pod++) {
                attachAPod(pod);
            }
            await().atMost(DELIVERED_WITHIN).until(() -> consumersOnTheSubscription() == PODS);
            assertThat(subscriptionsOnTheTopic())
                    .as("one subscription, shared: a second queue on the topic would deliver every event twice")
                    .hasSize(1);

            final List<String> groups = new ArrayList<>();
            for (int sent = 0; sent < EVENTS; sent++) {
                final UUID groupId = UUID.randomUUID();
                groups.add(groupId.toString());
                publish(GROUP_PROSECUTION_CASES_CREATED, event(groupId));
            }

            await().atMost(DELIVERED_WITHIN).until(() -> delivered.size() >= EVENTS);
            final Map<String, Long> timesDelivered = groups.stream().collect(Collectors.toMap(
                    Function.identity(),
                    group -> delivered.stream().filter(body -> body.contains(group)).count()));
            assertThat(delivered).hasSize(EVENTS);
            assertThat(timesDelivered).allSatisfy((group, times) -> assertThat(times)
                    .as("the event for group %s, delivered once across the pair", group).isOne());
        }

        private void attachAPod(final int number) {
            final SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("pod-" + number);
            endpoint.setDestination(TOPIC);
            endpoint.setSubscription(subscription);
            endpoint.setSelector(selector);
            endpoint.setMessageListener(this::capture);
            final DefaultMessageListenerContainer pod =
                    publicEventListenerContainerFactory.createListenerContainer(endpoint);
            pods.add(pod);
            pod.afterPropertiesSet();
            pod.start();
        }

        private void capture(final Message message) {
            try {
                delivered.add(((TextMessage) message).getText());
            } catch (JMSException unreadable) {
                throw new IllegalStateException(unreadable);
            }
        }
    }
}
