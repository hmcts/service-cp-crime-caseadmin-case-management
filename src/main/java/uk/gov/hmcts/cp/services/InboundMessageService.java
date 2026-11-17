package uk.gov.hmcts.cp.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.inbound.InboundMessage;
import uk.gov.hmcts.cp.inbound.InboundMessageHandler;

/**
 * Applies messages from the {@code casemanagement.requests} Service Bus queue.
 *
 * <p>PLACEHOLDER: no message contract has been agreed for this queue yet, so every well-formed JSON
 * message is logged and completed. When the contract lands, validate it here (throw
 * {@link uk.gov.hmcts.cp.inbound.InvalidMessageException} to dead-letter) and route on
 * {@link InboundMessage#subject()} to the domain services.
 */
@Slf4j
@Service
public class InboundMessageService implements InboundMessageHandler {

    @Override
    public void handle(final InboundMessage message) {
        log.info("Service Bus message applied. messageId={} subject={} deliveryCount={}",
                message.messageId(), message.subject(), message.deliveryCount());
    }
}
