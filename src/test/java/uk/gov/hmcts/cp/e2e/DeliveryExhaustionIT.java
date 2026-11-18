package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.support.QueueConsumerHarness;
import uk.gov.hmcts.cp.support.QueueConsumerHarness.Behaviour;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;

/**
 * A message whose handler keeps failing is retried exactly MaxDeliveryCount times and then parked
 * by the BROKER, not lost and not retried for ever. This is what makes "abandon on unexpected
 * failure" safe, and it only holds while casemanagement.servicebus.max-delivery-count and the
 * queue's MaxDeliveryCount agree.
 */
@DisplayName("delivery exhaustion against the Service Bus emulator")
class DeliveryExhaustionIT {

    private static final Duration PARKED_WITHIN = Duration.ofSeconds(60);

    private QueueConsumerHarness harness;

    @AfterEach
    void stopConsuming() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("five failed deliveries, then the broker parks it as MaxDeliveryCountExceeded")
    void should_park_after_exactly_max_delivery_count_failed_deliveries() {
        final String messageId = ServiceBusEmulatorTestSupport.send("{\"groupId\":\"x\"}");
        harness = new QueueConsumerHarness().expect(messageId, Behaviour.FAIL).start();

        await().atMost(PARKED_WITHIN).pollInterval(Duration.ofSeconds(1)).until(() -> ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo("MaxDeliveryCountExceeded");
        assertThat(harness.deliveriesOf(messageId)).hasSize(ServiceBusEmulatorTestSupport.MAX_DELIVERY_COUNT);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE)).isEmpty();
    }
}
