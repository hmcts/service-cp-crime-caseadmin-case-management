package uk.gov.hmcts.cp.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.validRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.clients.ProgressionAddress;
import uk.gov.hmcts.cp.clients.ProgressionCaseSummary;
import uk.gov.hmcts.cp.clients.ProgressionGroupCaseResponse;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.entity.ResultingIntent;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;

class GroupCaseMapperTest {

    private final GroupCaseMapper mapper = new GroupCaseMapper();

    @Test
    void toEntity_maps_every_request_field() {
        final UUID courtroomId = UUID.randomUUID();
        final UUID parentGroupId = UUID.randomUUID();
        final CreateGroupCaseRequest request = validRequest()
                .courtroomId(courtroomId)
                .prosecutingAuthorityName("Example Council")
                .parentGroupId(parentGroupId)
                .build();

        final GroupCaseEntity entity = mapper.toEntity(request);

        assertThat(entity.getGroupId()).isEqualTo(request.getGroupId());
        assertThat(entity.getParentGroupId()).isEqualTo(parentGroupId);
        assertThat(entity.getLeadCaseId()).isEqualTo(request.getLeadCase().getLeadCaseId());
        assertThat(entity.getLeadCaseUrn()).isEqualTo(request.getLeadCase().getCaseUrn());
        assertThat(entity.getLeadDefendantName()).isEqualTo(request.getLeadCase().getDefendantName());
        assertThat(entity.getDateOfHearing()).isEqualTo(request.getDateOfHearing());
        assertThat(entity.getCourtHearingLocation()).isEqualTo("B01LY01");
        assertThat(entity.getCourtCentreId()).isEqualTo(request.getCourtCentreId());
        assertThat(entity.getCourtroomId()).isEqualTo(courtroomId);
        assertThat(entity.getProsecutingAuthorityCode()).isEqualTo("EXAMPLE01");
        assertThat(entity.getProsecutingAuthorityName()).isEqualTo("Example Council");
        assertThat(entity.getOffenceType()).isEqualTo("Council Tax");
        assertThat(entity.getCaseCount()).isEqualTo(250);
    }

    @Test
    void toEntity_defaults_resulting_intent_to_grant_when_not_sent() {
        final GroupCaseEntity entity = mapper.toEntity(validRequest().build());

        assertThat(entity.getResultingIntent()).isEqualTo(ResultingIntent.GRANT);
    }

    @Test
    void toEntity_keeps_resulting_intent_when_sent() {
        final GroupCaseEntity entity = mapper.toEntity(validRequest()
                .resultingIntent(uk.gov.hmcts.cp.openapi.model.ResultingIntent.WITHDRAW)
                .build());

        assertThat(entity.getResultingIntent()).isEqualTo(ResultingIntent.WITHDRAW);
    }

    @Test
    void toEntity_leaves_optional_fields_null_and_timestamps_to_persistence() {
        final GroupCaseEntity entity = mapper.toEntity(validRequest().build());

        assertThat(entity.getParentGroupId()).isNull();
        assertThat(entity.getCourtroomId()).isNull();
        assertThat(entity.getProsecutingAuthorityName()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
    }

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEAD_CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REFERRED_CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static GroupCaseEntity storedEntity(final int caseCount) {
        return GroupCaseEntity.builder()
                .groupId(GROUP_ID)
                .leadCaseId(LEAD_CASE_ID)
                .leadCaseUrn("TESTURN0001")
                .leadDefendantName("Example Defendant")
                .dateOfHearing(LocalDate.of(2026, 12, 1))
                .courtHearingLocation("B01LY01")
                .courtCentreId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                .prosecutingAuthorityCode("EXAMPLE01")
                .offenceType("Council Tax")
                .caseCount(caseCount)
                .resultingIntent(ResultingIntent.WITHDRAW)
                .createdAt(Instant.parse("2026-11-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-11-01T00:00:00Z"))
                .build();
    }

    private static ProgressionCaseSummary caseSummary() {
        final ProgressionCaseSummary summary = new ProgressionCaseSummary();
        summary.setCaseId(LEAD_CASE_ID);
        summary.setCaseUrn("TESTURN0001");
        summary.setDefendantName("Example Defendant");
        summary.setDefendantDoB(LocalDate.of(1980, 1, 1));
        final ProgressionAddress address = new ProgressionAddress();
        address.setAddress1("1 Example Street");
        address.setPostcode("AB1 2CD");
        summary.setAddress(address);
        return summary;
    }

    private static ProgressionGroupCaseResponse progressionResponse(final List<ProgressionCaseSummary> cases) {
        final ProgressionGroupCaseResponse response = new ProgressionGroupCaseResponse();
        response.setGroupId(GROUP_ID);
        response.setLeadCase(caseSummary());
        response.setCases(cases);
        return response;
    }

    @Test
    void toResponse_maps_every_field_with_no_mismatch() {
        final GroupCaseEntity entity = storedEntity(1);
        final GroupCaseResponse response =
                mapper.toResponse(entity, progressionResponse(List.of(caseSummary())), REFERRED_CASE_ID);

        assertThat(response.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(response.getProsecutingAuthority()).isEqualTo("EXAMPLE01");
        assertThat(response.getCourtHearingLocation()).isEqualTo("B01LY01");
        assertThat(response.getDateOfHearing()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(response.getOffenceType()).isEqualTo("Council Tax");
        assertThat(response.getReferredCaseId()).isEqualTo(REFERRED_CASE_ID);
        assertThat(response.getCaseCount()).isEqualTo(1);
        assertThat(response.getCreatedDate()).isEqualTo(entity.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(response.getResultingIntent())
                .isEqualTo(uk.gov.hmcts.cp.openapi.model.ResultingIntent.WITHDRAW);

        assertThat(response.getLeadCase().getCaseId()).isEqualTo(LEAD_CASE_ID);
        assertThat(response.getLeadCase().getCaseUrn()).isEqualTo("TESTURN0001");
        assertThat(response.getLeadCase().getDefendantName()).isEqualTo("Example Defendant");
        assertThat(response.getLeadCase().getDefendantDoB()).isEqualTo(LocalDate.of(1980, 1, 1));
        assertThat(response.getLeadCase().getAddress().getAddress1()).isEqualTo("1 Example Street");
        assertThat(response.getLeadCase().getAddress().getPostcode()).isEqualTo("AB1 2CD");

        assertThat(response.getCases()).hasSize(1);
        assertThat(response.getWarning()).isNull();
        assertThat(response.getWarningMessage()).isNull();
        assertThat(response.getWarningDetails()).isNull();
    }

    @Test
    void toResponse_case_count_mismatch_should_set_warning_fields() {
        final GroupCaseEntity entity = storedEntity(4);
        final GroupCaseResponse response =
                mapper.toResponse(entity, progressionResponse(List.of(caseSummary(), caseSummary())), null);

        assertThat(response.getWarning()).isEqualTo(GroupCaseResponse.WarningEnum.CASE_COUNT_MISMATCH);
        assertThat(response.getWarningMessage()).contains("4").contains("2");
        assertThat(response.getWarningDetails().getStoredCaseCount()).isEqualTo(4);
        assertThat(response.getWarningDetails().getActualCaseCount()).isEqualTo(2);
    }

    @Test
    void toResponse_missing_address_should_leave_address_null() {
        final ProgressionCaseSummary summary = caseSummary();
        summary.setAddress(null);
        final GroupCaseResponse response =
                mapper.toResponse(storedEntity(1), progressionResponse(List.of(summary)), null);

        assertThat(response.getCases().get(0).getAddress()).isNull();
    }
}
