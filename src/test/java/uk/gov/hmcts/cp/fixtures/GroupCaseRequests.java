package uk.gov.hmcts.cp.fixtures;

import java.time.LocalDate;
import java.util.UUID;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.LeadCase;

/**
 * Example create-group-case requests for tests. All values are made up.
 */
public final class GroupCaseRequests {

    public static final String EXAMPLE_URN = "TESTURN0001";
    public static final String EXAMPLE_DEFENDANT = "Example Defendant";

    private GroupCaseRequests() {
    }

    /** A valid initial submission with every optional field left out. */
    public static CreateGroupCaseRequest.CreateGroupCaseRequestBuilder validRequest() {
        return CreateGroupCaseRequest.builder()
                .groupId(UUID.randomUUID())
                .leadCase(LeadCase.builder()
                        .leadCaseId(UUID.randomUUID())
                        .caseUrn(EXAMPLE_URN)
                        .defendantName(EXAMPLE_DEFENDANT)
                        .defendantDoB(LocalDate.of(1980, 1, 1))
                        .build())
                .dateOfHearing(LocalDate.of(2026, 12, 1))
                .caseCount(250)
                .offenceType("Council Tax")
                .courtHearingLocation("B01LY01")
                .courtCentreId(UUID.randomUUID())
                .prosecutingAuthority("EXAMPLE01");
    }
}
