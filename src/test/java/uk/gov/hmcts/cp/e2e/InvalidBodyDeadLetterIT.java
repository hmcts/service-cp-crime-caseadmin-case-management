package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.support.QueueConsumerHarness;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;

/**
 * A body that can never be read is parked on its first delivery, before the handler sees it, rather
 * than spending the queue's delivery budget on redeliveries that cannot succeed (the counterpart of
 * yot-results-distribution's ContractValidationDeadLetterIT).
 */
@DisplayName("an unreadable body against the Service Bus emulator")
class InvalidBodyDeadLetterIT {

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(30);

    private QueueConsumerHarness harness;

    @AfterEach
    void stopConsuming() {
        if (harness != null) {
            harness.close();
        }
    }

    @ParameterizedTest(name = "body: {0}")
    @ValueSource(strings = {"not json", "[1,2,3]", "\"a string\""})
    @DisplayName("is parked immediately, without reaching the handler")
    void should_park_an_unreadable_body_on_its_first_delivery(final String body) {
        final String messageId = ServiceBusEmulatorTestSupport.send(body);
        harness = new QueueConsumerHarness().start();

        await().atMost(SETTLED_WITHIN).pollInterval(Duration.ofSeconds(1)).until(() -> ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo("VALIDATION");
        assertThat(parked.getDeliveryCount()).as("parked on the first delivery").isLessThanOrEqualTo(1);
        assertThat(harness.deliveriesOf(messageId)).as("the handler never saw it").isEmpty();
    }
}
