package uk.gov.hmcts.cp.inbound;

/**
 * Applies one Service Bus message to the domain. The seam between the transport
 * ({@link CaseManagementMessageListener}) and the business logic.
 *
 * <p>Contract with the listener, which turns the outcome into a broker settlement:
 * <ul>
 *   <li>returns normally: the message is COMPLETED;</li>
 *   <li>throws {@link InvalidMessageException}: the message can never succeed and is DEAD-LETTERED
 *       now;</li>
 *   <li>throws any other {@link RuntimeException}: the message is ABANDONED and redelivered, until the
 *       queue's MaxDeliveryCount moves it to the dead-letter queue.</li>
 * </ul>
 * Handlers must be idempotent: Service Bus delivers at least once.
 */
@FunctionalInterface
public interface InboundMessageHandler {

    /** Applies the message, or throws as described on the interface. */
    void handle(InboundMessage message);
}
