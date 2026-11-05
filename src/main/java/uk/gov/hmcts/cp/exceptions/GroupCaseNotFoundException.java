package uk.gov.hmcts.cp.exceptions;

import java.io.Serial;
import java.util.UUID;

/**
 * Thrown when a referenced group case, such as a parent group, does not exist. Mapped to 404.
 */
public class GroupCaseNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given group id. */
    public GroupCaseNotFoundException(final UUID groupId) {
        super("Group case " + groupId + " not found");
    }
}
