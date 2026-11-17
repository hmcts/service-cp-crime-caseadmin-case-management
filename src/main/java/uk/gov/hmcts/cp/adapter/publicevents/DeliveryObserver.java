package uk.gov.hmcts.cp.adapter.publicevents;

/**
 * Told that the durable subscription handed this service a message, before any filtering, so health
 * can report how long the subscription has been silent without the listener holding an actuator type.
 */
@FunctionalInterface
public interface DeliveryObserver {

    /** An observer that does nothing. */
    DeliveryObserver NONE = () -> {
    };

    /** Records that a message was delivered on the subscription, whatever it turns out to be. */
    void recordDelivery();
}
