package uk.gov.hmcts.cp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;

/**
 * The system user case management acts as when it sends commands to Progression, from
 * {@code casemanagement.system-user-id}. A missing id does not stop the application starting;
 * only the calls that need it fail.
 */
@Component
public class CaseManagementSystemUser {

    private final String systemUserId;

    /** Creates the holder from the configured id, which may be blank. */
    public CaseManagementSystemUser(@Value("${casemanagement.system-user-id:}") final String systemUserId) {
        this.systemUserId = systemUserId;
    }

    /**
     * Returns the configured system user id.
     *
     * @throws SystemUserNotConfiguredException if no id is configured
     */
    public String getSystemUserId() {
        if (systemUserId == null || systemUserId.isBlank()) {
            throw new SystemUserNotConfiguredException();
        }
        return systemUserId;
    }
}
