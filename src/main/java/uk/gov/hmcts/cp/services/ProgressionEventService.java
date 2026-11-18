package uk.gov.hmcts.cp.services;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies progression's public events to this service.
 *
 * <p>Current behaviour: logs the whole event at INFO. Put any real reaction to the group's cases
 * being created in {@link #groupProsecutionCasesCreated(UUID, String)}, keeping it idempotent.
 */
@Slf4j
@Service
public class ProgressionEventService implements ProgressionEventSink {

    @Override
    public void groupProsecutionCasesCreated(final UUID groupId, final String event) {
        log.info("Group prosecution cases created in progression. groupId={} event={}", groupId, event);
    }
}
