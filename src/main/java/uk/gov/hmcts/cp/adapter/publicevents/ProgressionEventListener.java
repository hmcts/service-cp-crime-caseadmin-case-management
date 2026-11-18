package uk.gov.hmcts.cp.adapter.publicevents;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PublicEventsConfig;
import uk.gov.hmcts.cp.services.ProgressionEventSink;

/**
 * The shared durable subscription on {@code public.event} for progression's public events (the same
 * shape as yot-results-distribution's DocumentEventListener).
 *
 * <p>The broker's selector is the coarse filter (CPPNAME); this class re-checks the name against the
 * envelope, validates the payload, and hands it to {@link ProgressionEventSink}. A message that can
 * never be applied is acknowledged and dropped (redelivering it would not help); a failure to APPLY
 * is rethrown so the transacted session rolls back and the broker offers it again.
 */
@Slf4j
@SuppressWarnings("PMD.OnlyOneReturn")
public class ProgressionEventListener {

    /** The JMS property the CQRS framework puts the event name in; the selector filters on it. */
    public static final String EVENT_NAME_PROPERTY = "CPPNAME";
    public static final String GROUP_PROSECUTION_CASES_CREATED =
            "public.progression.group-prosecution-cases-created";

    private static final String GROUP_ID = "groupId";

    private final ProgressionEventSink sink;
    private final DeliveryObserver deliveries;

    public ProgressionEventListener(final ProgressionEventSink sink, final DeliveryObserver deliveries) {
        this.sink = sink;
        this.deliveries = deliveries;
    }

    @JmsListener(
            destination = "${casemanagement.publicevents.topic}",
            subscription = "${casemanagement.publicevents.subscription}",
            selector = "${casemanagement.publicevents.selector}",
            containerFactory = PublicEventsConfig.LISTENER_CONTAINER_FACTORY)
    public void onPublicEvent(final Message message) {
        deliveries.recordDelivery();
        // Taken as a plain Message: a TextMessage parameter would make Spring fail the conversion of
        // any other type, roll back and redeliver it for ever. The framework publishes JSON
        // TextMessages; anything else can never be applied, so it is acknowledged and dropped.
        if (!(message instanceof TextMessage text)) {
            log.warn("A public event was not a TextMessage, so it is acknowledged and dropped. type={}",
                    message.getClass().getName());
            return;
        }
        final String eventName;
        final String body;
        try {
            eventName = text.getStringProperty(EVENT_NAME_PROPERTY);
            body = text.getText();
        } catch (final JMSException unreadable) {
            log.warn("A public event could not be read off the subscription, so it is acknowledged "
                    + "and dropped. cause={}", unreadable.getClass().getName());
            return;
        }
        if (!GROUP_PROSECUTION_CASES_CREATED.equals(eventName)) {
            log.debug("A public event the selector does not name reached the subscription: {}.",
                    eventName);
            return;
        }
        final PublicEventEnvelope envelope;
        try {
            envelope = PublicEventEnvelope.parse(body);
        } catch (final JacksonException | IllegalArgumentException notAnEnvelope) {
            log.warn("A {} was not a readable JsonEnvelope, so it is acknowledged and dropped. "
                    + "cause={}", eventName, notAnEnvelope.getClass().getName());
            return;
        }
        if (!eventName.equals(envelope.metadataName())) {
            log.warn("A public event's CPPNAME and its envelope disagree, so it is acknowledged and "
                    + "dropped: header={} envelope={}", eventName, envelope.metadataName());
            return;
        }
        groupProsecutionCasesCreated(envelope.payload(), body);
    }

    private void groupProsecutionCasesCreated(final JsonNode payload, final String event) {
        final UUID groupId = uuid(payload, GROUP_ID);
        if (groupId == null) {
            log.warn("A {} named no valid groupId, so it is acknowledged and dropped.",
                    GROUP_PROSECUTION_CASES_CREATED);
            return;
        }
        sink.groupProsecutionCasesCreated(groupId, event);
    }

    // S7467: the exception is named for readability; an unparseable id is simply "no id".
    @SuppressWarnings("java:S7467")
    private static UUID uuid(final JsonNode payload, final String field) {
        final JsonNode value = payload.path(field);
        if (!value.isString()) {
            return null;
        }
        try {
            return UUID.fromString(value.stringValue());
        } catch (final IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
