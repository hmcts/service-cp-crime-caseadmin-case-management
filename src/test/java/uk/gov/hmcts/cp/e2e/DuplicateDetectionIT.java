package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.support.QueueConsumerHarness;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;

/**
 * The queue is declared with RequiresDuplicateDetection (5 minute window): a sender that republishes
 * under the same message id inside the window is absorbed by the broker, so the handler sees it once.
 * This guards the queue declaration in docker/servicebus-emulator/config.json as much as the code.
 */
@DisplayName("duplicate detection on the Service Bus emulator queue")
class DuplicateDetectionIT {

    private static final Duration CONSUMED_WITHIN = Duration.ofSeconds(30);

    private QueueConsumerHarness harness;

    @AfterEach
    void stopConsuming() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a republish under an id the broker has seen never reaches the handler")
    void should_discard_a_republished_message_id_inside_the_detection_window() {
        final String duplicated = UUID.randomUUID().toString();
        ServiceBusEmulatorTestSupport.send(duplicated, "{\"copy\":1}");
        ServiceBusEmulatorTestSupport.send(duplicated, "{\"copy\":2}");
        // Sent last with one consumer: once it has been handled, anything sent before it has been too.
        final String sentinel = ServiceBusEmulatorTestSupport.send("{\"sentinel\":true}");
        harness = new QueueConsumerHarness().start();

        await().atMost(CONSUMED_WITHIN).pollInterval(Duration.ofMillis(500))
                .until(() -> !harness.deliveriesOf(sentinel).isEmpty());

        assertThat(harness.deliveriesOf(duplicated)).hasSize(1);
    }
}
