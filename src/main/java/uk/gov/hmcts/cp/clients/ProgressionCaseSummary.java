package uk.gov.hmcts.cp.clients;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A member case as returned by Progression's group-cases query. */
@Getter
@Setter
public class ProgressionCaseSummary {

    private UUID caseId;
    private String caseUrn;
    private String defendantName;
    private LocalDate defendantDoB;
    private ProgressionAddress address;
}
