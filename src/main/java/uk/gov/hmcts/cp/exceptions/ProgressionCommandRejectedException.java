package uk.gov.hmcts.cp.exceptions;

import java.io.Serial;
import org.springframework.http.HttpStatusCode;

/**
 * Thrown when Progression does not accept a command with 202, or cannot be reached. Nothing has
 * been changed in case management when this is thrown. Mapped to 500.
 */
public class ProgressionCommandRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the exception for a non-202 response from Progression. */
    public ProgressionCommandRejectedException(final HttpStatusCode status) {
        super("Progression did not accept the command: HTTP " + status.value());
    }

    /** Creates the exception for a failure to reach Progression. */
    public ProgressionCommandRejectedException(final Throwable cause) {
        super("Progression could not be reached", cause);
    }
}
