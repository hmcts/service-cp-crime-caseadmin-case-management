package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.support.ServiceTestSupport;

/**
 * A pod that starts while the broker is unreachable must still start (a pod that cannot start cannot
 * say why), report the broker as down, and begin consuming once the broker appears, without a
 * restart. A consumer that only ever connected at startup would sit there idle for ever.
 */
@DisplayName("starting while the broker is unreachable")
class StartupWithQueueDownIT {

    private static final Duration NOTICED_WITHIN = Duration.ofSeconds(90);
    /** The budget, measured from the moment the queue appears. */
    private static final Duration CONSUMED_WITHIN = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(1);

    private ConfigurableApplicationContext service;

    @AfterEach
    void restore() {
        ServiceBusEmulatorTestSupport.restore();
        if (service != null) {
            service.close();
        }
    }

    @Test
    @DisplayName("it starts, reports the broker down, and consumes once the queue appears")
    void should_start_report_the_broker_down_and_consume_once_it_returns() {
        ServiceBusEmulatorTestSupport.container();
        ServiceBusEmulatorTestSupport.disconnect();

        // A short staleness window: it is also the grace a consumer gets before "never once heard
        // from the broker" is reported. The deployed minute would only make the suite wait longer.
        assertThatCode(() -> service = ServiceTestSupport.start(
                Map.of("casemanagement.servicebus.health-staleness", "5s"))).doesNotThrowAnyException();
        await().atMost(NOTICED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(ServiceTestSupport.serviceBusHealth(service)));

        ServiceBusEmulatorTestSupport.reconnect();
        final String messageId = ServiceBusEmulatorTestSupport.send("{\"phase\":\"after-startup\"}");
        await().atMost(CONSUMED_WITHIN).pollInterval(POLL).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isEmpty()
                && ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isEmpty());
        await().atMost(CONSUMED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(ServiceTestSupport.serviceBusHealth(service)));
    }
}
