package uk.gov.hmcts.cp.mappers;

import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.clients.ProgressionAddress;
import uk.gov.hmcts.cp.clients.ProgressionCaseSummary;
import uk.gov.hmcts.cp.clients.ProgressionGroupCaseResponse;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.entity.ResultingIntent;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.DefendantAddress;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;
import uk.gov.hmcts.cp.openapi.model.GroupCaseWarningDetails;
import uk.gov.hmcts.cp.openapi.model.GroupMemberCase;
import uk.gov.hmcts.cp.openapi.model.LeadCase;

/**
 * Maps the create-group-case request to the group_case entity, and the stored entity plus
 * Progression's member cases to the view response. The lead defendant's date of birth is not
 * stored, and timestamps are set when the entity is persisted.
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

    /** Builds the group case view, combining the stored row with Progression's member cases. */
    public GroupCaseResponse toResponse(
            final GroupCaseEntity entity,
            final ProgressionGroupCaseResponse progressionResponse,
            final UUID referredCaseId) {

        final int storedCaseCount = entity.getCaseCount();
        final int actualCaseCount = progressionResponse.getCases().size();

        final GroupCaseResponse.GroupCaseResponseBuilder response = GroupCaseResponse.builder()
                .groupId(entity.getGroupId())
                .prosecutingAuthority(entity.getProsecutingAuthorityCode())
                .courtHearingLocation(entity.getCourtHearingLocation())
                .dateOfHearing(entity.getDateOfHearing())
                .offenceType(entity.getOffenceType())
                .leadCase(toGroupMemberCase(progressionResponse.getLeadCase()))
                .referredCaseId(referredCaseId)
                .cases(progressionResponse.getCases().stream().map(this::toGroupMemberCase).toList())
                .caseCount(storedCaseCount)
                .createdDate(entity.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate())
                .resultingIntent(uk.gov.hmcts.cp.openapi.model.ResultingIntent.valueOf(
                        entity.getResultingIntent().name()));

        if (storedCaseCount != actualCaseCount) {
            response.warning(GroupCaseResponse.WarningEnum.CASE_COUNT_MISMATCH)
                    .warningMessage(("Stored case count (%d) does not match the number of active cases "
                            + "returned by Progression (%d). The two services may be out of sync.")
                            .formatted(storedCaseCount, actualCaseCount))
                    .warningDetails(GroupCaseWarningDetails.builder()
                            .storedCaseCount(storedCaseCount)
                            .actualCaseCount(actualCaseCount)
                            .build());
        }

        return response.build();
    }

    private GroupMemberCase toGroupMemberCase(final ProgressionCaseSummary caseSummary) {
        return GroupMemberCase.builder()
                .caseId(caseSummary.getCaseId())
                .caseUrn(caseSummary.getCaseUrn())
                .defendantName(caseSummary.getDefendantName())
                .defendantDoB(caseSummary.getDefendantDoB())
                .address(toDefendantAddress(caseSummary.getAddress()))
                .build();
    }

    private DefendantAddress toDefendantAddress(final ProgressionAddress address) {
        return address == null ? null : DefendantAddress.builder()
                .address1(address.getAddress1())
                .address2(address.getAddress2())
                .address3(address.getAddress3())
                .address4(address.getAddress4())
                .address5(address.getAddress5())
                .postcode(address.getPostcode())
                .build();
    }
}
