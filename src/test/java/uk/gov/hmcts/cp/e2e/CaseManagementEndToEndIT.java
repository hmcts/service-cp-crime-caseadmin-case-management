package uk.gov.hmcts.cp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.inbound.ConsumerLifecycleController;
import uk.gov.hmcts.cp.services.InboundMessageService;
import uk.gov.hmcts.cp.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.support.ServiceTestSupport;

/**
 * The deployed wiring, end to end: the whole application (consumer switched on, real Postgres) takes
 * messages off the emulator's queue and settles them, driven and read only through the queue, the
 * service's own log and the health endpoint.
 */
@DisplayName("the Service Bus consumer, end to end")
class CaseManagementEndToEndIT {

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static ConfigurableApplicationContext service;
    private static ListAppender<ILoggingEvent> handled;
    private static Logger handlerLog;

    @BeforeAll
    static void startService() {
        service = ServiceTestSupport.start(Map.of());
        // After start: Spring re-initialises logback from logback.xml while it boots.
        handlerLog = (Logger) LoggerFactory.getLogger(InboundMessageService.class);
        handled = new ListAppender<>();
        handled.start();
        handlerLog.addAppender(handled);
    }

    @AfterAll
    static void stopService() {
        if (handlerLog != null) {
            handlerLog.detachAppender(handled);
        }
        if (service != null) {
            service.close();
        }
    }

    private static boolean handlerApplied(final String messageId) {
        return handled.list.stream().anyMatch(line -> line.getFormattedMessage().contains("messageId=" + messageId));
    }

    @Test
    @DisplayName("the consumer is running once the application has started")
    void should_start_consuming_with_the_application() {
        assertThat(service.getBean(ConsumerLifecycleController.class).isRunning()).isTrue();
    }

    @Test
    @DisplayName("a JSON message reaches the handler and leaves the queue")
    void should_apply_and_complete_a_well_formed_message() {
        final String messageId = ServiceBusEmulatorTestSupport.send(
                "{\"groupId\":\"1b4e28ba-2fa1-11d2-883f-0016d3cca427\"}");

        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> handlerApplied(messageId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isEmpty());

        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
        assertThat(ServiceTestSupport.serviceBusHealth(service)).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("an unreadable message is parked and never reaches the handler")
    void should_dead_letter_an_unreadable_message() {
        final String messageId = ServiceBusEmulatorTestSupport.send("this is not json");

        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo("VALIDATION");
        assertThat(parked.getDeadLetterErrorDescription()).isEqualTo("message body is not valid JSON");
        assertThat(handlerApplied(messageId)).isFalse();
    }
}
