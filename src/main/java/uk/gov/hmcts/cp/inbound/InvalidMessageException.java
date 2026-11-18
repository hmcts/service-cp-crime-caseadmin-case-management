package uk.gov.hmcts.cp.inbound;

import java.io.Serial;

/** A message that will never succeed however often it is redelivered, so it is dead-lettered now. */
public class InvalidMessageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Names why the message was refused; the text becomes the dead-letter error description. */
    public InvalidMessageException(final String reason) {
        super(reason);
    }

    /** As above, keeping what made the message unreadable as the cause, for the stack trace. */
    public InvalidMessageException(final String reason, final Throwable cause) {
        super(reason, cause);
    }
}
