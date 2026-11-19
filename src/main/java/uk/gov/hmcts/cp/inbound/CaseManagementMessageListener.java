package uk.gov.hmcts.cp.inbound;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.monitoring.PublishMetricsService;

/**
 * Turns one Service Bus delivery into exactly one settlement: complete, abandon or dead-letter.
 * Same responsibilities as yot-results-distribution's {@code YotResultsDistributionMessageListener}:
 * read the body, hand it to the domain, and settle by the outcome. The processor runs with
 * auto-complete disabled, so this class is the only thing that settles.
 */
@Slf4j
public class CaseManagementMessageListener {

    private static final String MESSAGE_ID = "messageId";
    private static final String SUBJECT = "subject";
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final String REASON_VALIDATION = "VALIDATION";
    private static final String REASON_UNEXPECTED = "UNEXPECTED_FAILURE";
    /** The {@code source} dimension of exceptions this listener publishes to Azure Monitor. */
    private static final String METRIC_SOURCE = "servicebus";
    /** The {@code outcome} dimension of a dead-lettered message. */
    private static final String OUTCOME_DEADLETTER = "deadletter";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final InboundMessageHandler handler;
    private final ServiceBusHealthIndicator health;
    private final PublishMetricsService publishMetricsService;

    public CaseManagementMessageListener(
            final InboundMessageHandler handler, final ServiceBusHealthIndicator health,
            final PublishMetricsService publishMetricsService) {
        this.handler = handler;
        this.health = health;
        this.publishMetricsService = publishMetricsService;
    }

    /** Processes and settles one delivery. Never throws: a throw would hand settlement to the SDK. */
    public void onMessage(final ServiceBusReceivedMessageContext context) {
        final ServiceBusReceivedMessage message = context.getMessage();
        try {
            putIfPresent(MESSAGE_ID, message.getMessageId());
            putIfPresent(SUBJECT, message.getSubject());
            log.info("Delivery received. messageId={} subject={} deliveryCount={}",
                    message.getMessageId(), message.getSubject(), message.getDeliveryCount());
            settle(context, decide(message));
        } finally {
            MDC.remove(MESSAGE_ID);
            MDC.remove(SUBJECT);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private SettlementDecision decide(final ServiceBusReceivedMessage message) {
        SettlementDecision decision;
        try {
            handler.handle(new InboundMessage(message.getMessageId(), message.getSubject(),
                    message.getDeliveryCount(), parse(message)));
            decision = new SettlementDecision.Complete();
        } catch (InvalidMessageException invalid) {
            log.error("Message refused; parking it. reason={}", invalid.getMessage());
            publishMetricsService.publishExceptionIfEnabled(METRIC_SOURCE, invalid, null);
            decision = new SettlementDecision.DeadLetter(REASON_VALIDATION, invalid.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("Delivery failed unexpectedly; returning it for redelivery. type={}",
                    unexpected.getClass().getName(), unexpected);
            publishMetricsService.publishExceptionIfEnabled(METRIC_SOURCE, unexpected, null);
            decision = new SettlementDecision.Abandon(REASON_UNEXPECTED);
        }
        return decision;
    }

    private static JsonNode parse(final ServiceBusReceivedMessage message) {
        try {
            final JsonNode body = MAPPER.readTree(message.getBody().toString());
            if (body == null || !body.isObject()) {
                throw new InvalidMessageException("message body is not a JSON object");
            }
            return body;
        } catch (JacksonException notJson) {
            throw new InvalidMessageException("message body is not valid JSON", notJson);
        }
    }

    private void settle(
            final ServiceBusReceivedMessageContext context, final SettlementDecision decision) {
        switch (decision) {
            case SettlementDecision.Complete() -> settleThenLog("complete", context::complete,
                    () -> log.info("Delivery completed."));
            case SettlementDecision.Abandon(String reason) -> settleThenLog("abandon", context::abandon,
                    () -> log.info("Delivery returned for redelivery. reason={}", reason));
            case SettlementDecision.DeadLetter(String reason, String description) -> settleThenLog(
                    "deadletter",
                    () -> context.deadLetter(new DeadLetterOptions()
                            .setDeadLetterReason(reason)
                            .setDeadLetterErrorDescription(description)),
                    () -> {
                        log.warn("Delivery parked on the dead-letter queue. reason={} detail={}",
                                reason, description);
                        // Explicitly chosen for Azure Monitor: a parked message needs someone to look at it.
                        // Counts only what this listener parks. A message the broker parks itself after
                        // MaxDeliveryCount abandons never comes back here: see docs/AzureMonitor.md.
                        publishMetricsService.publishMessageSettlement(OUTCOME_DEADLETTER, reason);
                    });
        }
    }

    /** Settles once via {@link #accepted}, and logs the outcome only if the broker took it. */
    private void settleThenLog(
            final String operation, final Runnable brokerCall, final Runnable onSettled) {
        if (accepted(operation, brokerCall)) {
            onSettled.run();
        }
    }

    /**
     * Makes the settlement call once. A refused settlement is never retried here: the lock expires
     * and the broker redelivers, which is the recovery path the handler's idempotency is there for.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private boolean accepted(final String operation, final Runnable brokerCall) {
        boolean settled = false;
        try {
            brokerCall.run();
            settled = true;
            health.recordTraffic();
        } catch (RuntimeException refused) {
            if (lockLost(refused)) {
                log.error("The delivery lock was lost before the settlement was accepted; recovery "
                        + "is the broker's redelivery. operation={}", operation);
            } else {
                log.error("The broker refused the settlement; the delivery will come round again. "
                        + "operation={} type={}", operation, refused.getClass().getName());
                health.recordError("SETTLEMENT", refused);
            }
        }
        return settled;
    }

    private static boolean lockLost(final Throwable refusal) {
        boolean lost = false;
        Throwable current = refusal;
        for (int depth = 0; !lost && current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            lost = switch (current) {
                case ServiceBusException serviceBus ->
                    serviceBus.getReason() == ServiceBusFailureReason.MESSAGE_LOCK_LOST;
                case AmqpException amqp ->
                    amqp.getErrorCondition() == AmqpErrorCondition.MESSAGE_LOCK_LOST;
                default -> false;
            };
            current = current.getCause();
        }
        return lost;
    }

    private static void putIfPresent(final String key, final String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
