package uk.gov.hmcts.cp.clients;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * Body of Progression's {@code remove-case-from-group-cases} command. newLeadCaseId is left out
 * entirely when the lead case is not being removed, because Progression's schema does not allow
 * extra or null properties.
 *
 * @param groupId the group the case is removed from
 * @param newLeadCaseId the case that becomes the group's lead, only when the lead is removed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemoveCaseFromGroupCommand(UUID groupId, UUID newLeadCaseId) {
}
