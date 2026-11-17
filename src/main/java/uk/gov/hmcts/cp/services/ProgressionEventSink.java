package uk.gov.hmcts.cp.services;

import java.util.UUID;

/**
 * Where the progression public events this service subscribes to are applied, already parsed and
 * checked by {@link uk.gov.hmcts.cp.adapter.publicevents.ProgressionEventListener}. The listener knows
 * JMS; this knows the domain (the same split as yot-results-distribution's DocumentOutcomeSink).
 *
 * <p>A throw rolls back the transacted JMS session and the broker redelivers the event, so
 * implementations must be idempotent.
 */
public interface ProgressionEventSink {

    /**
     * {@code public.progression.group-prosecution-cases-created}: the group's cases now exist in progression.
     *
     * @param groupId the validated groupId from the payload
     * @param event the whole event exactly as received (JsonEnvelope, including {@code _metadata})
     */
    void groupProsecutionCasesCreated(UUID groupId, String event);
}
