package uk.gov.hmcts.cp.clients;

import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Response body of Progression's {@code GET prosecutioncases/group/{groupId}}, media type
 * {@code application/vnd.progression.query.group-cases+json}. Removed cases are not included.
 */
@Getter
@Setter
public class ProgressionGroupCaseResponse {

    private UUID groupId;
    private ProgressionCaseSummary leadCase;
    private List<ProgressionCaseSummary> cases;
}
