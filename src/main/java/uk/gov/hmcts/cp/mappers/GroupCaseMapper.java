package uk.gov.hmcts.cp.mappers;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.entity.ResultingIntent;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.LeadCase;

/**
 * Maps the create-group-case request to the group_case entity. The lead defendant's date of
 * birth is not stored, and timestamps are set when the entity is persisted.
 */
@Component
public class GroupCaseMapper {

    /** Builds a new entity from the request; result intent defaults to GRANT when not sent. */
    public GroupCaseEntity toEntity(final CreateGroupCaseRequest request) {
        final LeadCase leadCase = request.getLeadCase();
        final ResultingIntent resultingIntent = request.getResultingIntent() == null
                ? ResultingIntent.GRANT
                : ResultingIntent.valueOf(request.getResultingIntent().name());

        return GroupCaseEntity.builder()
                .groupId(request.getGroupId())
                .parentGroupId(request.getParentGroupId())
                .leadCaseId(leadCase.getLeadCaseId())
                .leadCaseUrn(leadCase.getCaseUrn())
                .leadDefendantName(leadCase.getDefendantName())
                .dateOfHearing(request.getDateOfHearing())
                .courtHearingLocation(request.getCourtHearingLocation())
                .courtCentreId(request.getCourtCentreId())
                .courtroomId(request.getCourtroomId())
                .prosecutingAuthorityCode(request.getProsecutingAuthority())
                .prosecutingAuthorityName(request.getProsecutingAuthorityName())
                .offenceType(request.getOffenceType())
                .caseCount(request.getCaseCount())
                .resultingIntent(resultingIntent)
                .build();
    }
}
