package uk.gov.hmcts.cp.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.validRequest;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.entity.ResultingIntent;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;

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
}
