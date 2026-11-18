package uk.gov.hmcts.cp.support;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.cp.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.inbound.CaseManagementMessageListener;
import uk.gov.hmcts.cp.inbound.InboundMessage;
import uk.gov.hmcts.cp.inbound.InvalidMessageException;

/**
 * The production {@link CaseManagementMessageListener}, consuming the emulator's queue through a
 * processor built as ServiceBusConsumerConfig builds it (PEEK_LOCK, auto-complete off), in front of a
 * handler a test programs per message id. Messages a test did not program (leftovers from another
 * suite) are completed, so they drain instead of interfering.
 */
@Slf4j
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class QueueConsumerHarness implements AutoCloseable {

    /** What the handler does with a given message. */
    public enum Behaviour { COMPLETE, FAIL, REFUSE }

    /** One delivery the handler saw. */
    public record Delivery(String messageId, long deliveryCount) {
    }

    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final List<Delivery> observed = new CopyOnWriteArrayList<>();
    private final ServiceBusProcessorClient processor;

    public QueueConsumerHarness() {
        final CaseManagementMessageListener listener = new CaseManagementMessageListener(
                this::handle, new ServiceBusHealthIndicator(Duration.ofSeconds(60), Clock.systemUTC()));
        processor = new ServiceBusClientBuilder()
                .connectionString(ServiceBusEmulatorTestSupport.connectionString())
                .processor()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .maxConcurrentCalls(1)
                .disableAutoComplete()
                .processMessage(listener::onMessage)
                .processError(error -> log.warn("Processor error in the harness: {}", error.getErrorSource()))
                .buildProcessorClient();
    }

    private void handle(final InboundMessage message) {
        observed.add(new Delivery(message.messageId(), message.deliveryCount()));
        switch (behaviours.getOrDefault(message.messageId(), Behaviour.COMPLETE)) {
            case FAIL -> throw new IllegalStateException("the handler failed, as the test asked");
            case REFUSE -> throw new InvalidMessageException("refused by the test");
            case COMPLETE -> {
                // applied
            }
        }
    }

    /** Programs the handler's answer for one message id. */
    public QueueConsumerHarness expect(final String messageId, final Behaviour behaviour) {
        behaviours.put(messageId, behaviour);
        return this;
    }

    /** Starts consuming. */
    public QueueConsumerHarness start() {
        processor.start();
        return this;
    }

    /** Every delivery of one message the handler saw, in order. */
    public List<Delivery> deliveriesOf(final String messageId) {
        return observed.stream().filter(delivery -> messageId.equals(delivery.messageId())).toList();
    }

    @Override
    public void close() {
        processor.close();
    }
}
