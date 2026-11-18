package uk.gov.hmcts.cp.inbound;

/**
 * What the listener tells the broker about one delivery. Every delivery ends in exactly one of these,
 * and the processor runs with auto-complete disabled, so nothing else settles a message.
 */
public sealed interface SettlementDecision {

    /** Done; remove the message from the queue. */
    record Complete() implements SettlementDecision {
    }

    /** Not done this time; release the lock so the broker redelivers it. */
    record Abandon(String reason) implements SettlementDecision {
    }

    /** Can never succeed; park it on the dead-letter sub-queue. */
    record DeadLetter(String reason, String description) implements SettlementDecision {
    }
}
