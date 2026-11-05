package uk.gov.hmcts.cp.exceptions;

import java.io.Serial;
import java.util.UUID;

/**
 * Thrown when a group case is created with a groupId that is already stored. Mapped to 409.
 */
public class GroupCaseAlreadyExistsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given group id. */
    public GroupCaseAlreadyExistsException(final UUID groupId) {
        super("A group case with groupId " + groupId + " already exists");
    }
}
