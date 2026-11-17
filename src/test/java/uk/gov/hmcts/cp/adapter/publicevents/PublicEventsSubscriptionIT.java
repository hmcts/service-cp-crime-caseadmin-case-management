package uk.gov.hmcts.cp.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.adapter.publicevents.ProgressionEventListener.GROUP_PROSECUTION_CASES_CREATED;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.cp.config.PublicEventsConfig;
import uk.gov.hmcts.cp.config.PublicEventsHealthIndicator;
import uk.gov.hmcts.cp.services.ProgressionEventSink;

/**
 * The real subscription against an in-process Artemis broker: the event is published to the
 * {@code public.event} TOPIC with a CPPNAME property, exactly as the CQRS framework publishes it, and
 * must arrive through the broker-side selector, the shared durable subscription and the listener.
 */
@SpringBootTest(
        classes = PublicEventsSubscriptionIT.Slice.class,
        properties = {
            "spring.config.import=classpath:application-messaging.yml",
            "spring.artemis.mode=embedded",
            "spring.artemis.embedded.topics=public.event",
            "casemanagement.publicevents.enabled=true"
        })
class PublicEventsSubscriptionIT {

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

    private void publish(final String cppName, final String body) {
        jmsTemplate.convertAndSend("public.event", body, message -> {
            message.setStringProperty("CPPNAME", cppName);
            return message;
        });
    }

    @Test
    void published_event_reaches_the_sink_through_the_durable_subscription() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> "UP".equals(health.health().getStatus().getCode()));

        publish(GROUP_PROSECUTION_CASES_CREATED,
                ProgressionEventListenerTest.envelope(GROUP_PROSECUTION_CASES_CREATED, GROUP_ID.toString()));

        verify(sink, timeout(10_000)).groupProsecutionCasesCreated(eq(GROUP_ID), contains("\"_metadata\""));
        assertThat(health.health().getDetails().get("lastDeliveryAt")).isNotEqualTo("none");
    }

    @Test
    void events_the_selector_does_not_name_never_reach_the_listener() {
        publish("public.progression.prosecution-case-created",
                ProgressionEventListenerTest.envelope(GROUP_PROSECUTION_CASES_CREATED, GROUP_ID.toString()));

        verify(sink, after(2_000).never()).groupProsecutionCasesCreated(any(), any());
        verify(sink, never()).groupProsecutionCasesCreated(any(), any());
    }
}
