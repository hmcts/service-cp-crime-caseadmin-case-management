package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.support.QueueConsumerHarness;
import uk.gov.hmcts.cp.support.QueueConsumerHarness.Behaviour;
import uk.gov.hmcts.cp.support.QueueConsumerHarness.Delivery;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;

/**
 * Each settlement the listener makes, against the real broker: what the queue looks like afterwards
 * is the assertion, not what the listener says it did.
 */
@DisplayName("queue settlement against the Service Bus emulator")
class QueueSettlementIT {

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final String BODY = "{\"groupId\":\"1b4e28ba-2fa1-11d2-883f-0016d3cca427\"}";

    private QueueConsumerHarness harness;

    @AfterEach
    void stopConsuming() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a completed delivery leaves the queue and is never delivered again")
    void should_remove_a_completed_delivery_from_the_queue() {
        final String messageId = ServiceBusEmulatorTestSupport.send(BODY);
        harness = new QueueConsumerHarness().expect(messageId, Behaviour.COMPLETE).start();

        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> !harness.deliveriesOf(messageId).isEmpty());
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isEmpty());

        assertThat(harness.deliveriesOf(messageId)).hasSize(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
    }

    @Test
    @DisplayName("a delivery whose handler failed comes round again, counted")
    void should_redeliver_an_abandoned_delivery_with_an_incremented_count() {
        final String messageId = ServiceBusEmulatorTestSupport.send(BODY);
        harness = new QueueConsumerHarness().expect(messageId, Behaviour.FAIL).start();

        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> harness.deliveriesOf(messageId).size() >= 2);

        final List<Long> counts = harness.deliveriesOf(messageId).stream().map(Delivery::deliveryCount).toList();
        assertThat(counts.get(0)).isZero();
        assertThat(counts.get(1)).isEqualTo(counts.get(0) + 1);
    }

    @Test
    @DisplayName("a refused delivery lands on the dead-letter queue, saying why, after one delivery")
    void should_dead_letter_a_refused_delivery_with_its_reason_and_description() {
        final String messageId = ServiceBusEmulatorTestSupport.send(BODY);
        harness = new QueueConsumerHarness().expect(messageId, Behaviour.REFUSE).start();

        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo("VALIDATION");
        assertThat(parked.getDeadLetterErrorDescription()).isEqualTo("refused by the test");
        assertThat(harness.deliveriesOf(messageId)).hasSize(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE)).isEmpty();
    }
}
