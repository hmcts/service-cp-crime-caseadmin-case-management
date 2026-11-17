package uk.gov.hmcts.cp.inbound;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The only thing that starts or stops the Service Bus consumer. Intake begins once the whole context
 * (datasource, Flyway, web) is up, and stops first on shutdown so in-flight deliveries settle before
 * the beans they need are destroyed.
 *
 * <p>yot-results-distribution's controller additionally holds intake until its store answers and
 * suspends it on a store outage. That is deliberately not ported yet; this is the seam to add it.
 */
@Slf4j
public class ConsumerLifecycleController implements SmartLifecycle {

    /** Start last, stop first. */
    private static final int PHASE = Integer.MAX_VALUE - 100;

    private final ServiceBusProcessorClient processor;
    private final String queueName;

    public ConsumerLifecycleController(final ServiceBusProcessorClient processor, final String queueName) {
        this.processor = processor;
        this.queueName = queueName;
    }

    @Override
    public void start() {
        log.info("Starting the Service Bus consumer. queue={}", queueName);
        processor.start();
    }

    @Override
    public void stop() {
        log.info("Stopping the Service Bus consumer. queue={}", queueName);
        processor.stop();
    }

    @Override
    public boolean isRunning() {
        return processor.isRunning();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
