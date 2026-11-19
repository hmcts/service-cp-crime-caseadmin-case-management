package uk.gov.hmcts.cp.exceptions;

import java.io.Serial;
import uk.gov.hmcts.cp.monitoring.PublishToAzureMonitor;

/** Thrown when the system user id used for calls to Progression is not configured. Mapped to 500. */
@PublishToAzureMonitor
public class SystemUserNotConfiguredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public SystemUserNotConfiguredException() {
        super("System user id is not configured");
    }
}
