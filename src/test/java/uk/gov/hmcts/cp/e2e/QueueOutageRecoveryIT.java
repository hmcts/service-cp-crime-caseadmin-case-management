package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import uk.gov.hmcts.cp.inbound.InboundMessageHandler;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.support.ServiceTestSupport;

/**
 * The broker goes away under a running service and comes back; the pod neither restarts nor stays
 * broken. Modelled on yot-results-distribution's QueueOutageRecoveryIT.
 *
 * <p><strong>The outage is staged with work in hand.</strong> The SDK reports nothing at all about a
 * broker that goes away while the consumer is idle: it treats a lost connection as retryable and rolls
 * its message pump silently. The evidence health is built on is a round trip that FAILED, so there
 * has to be one: a delivery is held open, the transport is cut under it (Toxiproxy, which turns a race
 * into a sequence), and only then is it allowed to finish; its settlement is refused at once.
 *
 * <p>Recovery has a budget: consumption resumes within sixty seconds of the queue returning, which is
 * why the client's retry options are configured explicitly rather than inherited.
 */
@DisplayName("a broker outage under a running service")
class QueueOutageRecoveryIT {

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(90);
    private static final Duration RESUMES_WITHIN = Duration.ofSeconds(60);
    private static final Duration HELD_AT_MOST = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static final String HELD = "held-" + UUID.randomUUID();
    private static final CountDownLatch IN_FLIGHT = new CountDownLatch(1);
    private static final CountDownLatch RELEASE = new CountDownLatch(1);

    /** Holds the one message this suite names open until released; everything else applies at once. */
    static class HoldingHandler {
        @Bean
        @Primary
        InboundMessageHandler holdingHandler() {
            return message -> {
                if (HELD.equals(message.messageId())) {
                    IN_FLIGHT.countDown();
                    try {
                        RELEASE.await(HELD_AT_MOST.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    private ConfigurableApplicationContext service;

    @AfterEach
    void letGoAndBringTheBrokerBack() {
        RELEASE.countDown();
        ServiceBusEmulatorTestSupport.restore();
        if (service != null) {
            service.close();
        }
    }

    private static boolean consumed(final String messageId) {
        return ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isEmpty()
                && ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isEmpty();
    }

    @Test
    @DisplayName("the outage is reported while it lasts and consumption resumes when the broker returns")
    void should_report_the_outage_and_resume_consuming_when_the_queue_returns() throws InterruptedException {
        service = ServiceTestSupport.start(Map.of(), HoldingHandler.class);

        // A sequence, not a race: hold a delivery, cut the transport under it, then let it finish.
        ServiceBusEmulatorTestSupport.send(HELD, "{\"phase\":\"held\"}");
        assertThat(IN_FLIGHT.await(OBSERVED_WITHIN.toSeconds(), TimeUnit.SECONDS))
                .as("the delivery must genuinely be in flight before the transport is cut")
                .isTrue();
        ServiceBusEmulatorTestSupport.disconnect();
        RELEASE.countDown();

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(ServiceTestSupport.serviceBusHealth(service)));

        ServiceBusEmulatorTestSupport.restore();

        // Everything below is inside the sixty seconds, counted from the queue's return.
        final String after = ServiceBusEmulatorTestSupport.send("{\"phase\":\"after\"}");
        await().atMost(RESUMES_WITHIN).pollInterval(POLL).until(() -> consumed(after));
        assertThat(ServiceTestSupport.serviceBusHealth(service))
                .as("a delivery that succeeded is the answer to the error that preceded it")
                .isEqualTo(Status.UP);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(after, SubQueue.DEAD_LETTER_QUEUE))
                .as("an outage of the broker's is nobody's poison message")
                .isEmpty();
    }
}
