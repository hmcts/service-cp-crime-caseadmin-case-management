package uk.gov.hmcts.cp.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.validRequest;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.hmcts.cp.clients.ProgressionCaseSummary;
import uk.gov.hmcts.cp.clients.ProgressionClient;
import uk.gov.hmcts.cp.clients.ProgressionGroupCaseResponse;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.mappers.GroupCaseMapper;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;
import uk.gov.hmcts.cp.repository.GroupCaseRepository;

@ExtendWith(MockitoExtension.class)
class GroupCaseServiceTest {

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private GroupCaseRepository groupCaseRepository;

    @Mock
    private ProgressionClient progressionClient;

    private GroupCaseService groupCaseService;

    @BeforeEach
    void setUp() {
        groupCaseService = new GroupCaseService(groupCaseRepository, new GroupCaseMapper(), progressionClient);
    }

    private static GroupCaseEntity storedGroupCase(final int caseCount) {
        return GroupCaseEntity.builder()
                .groupId(GROUP_ID)
                .leadCaseId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .leadCaseUrn("TESTURN0001")
                .leadDefendantName("Example Defendant")
                .dateOfHearing(LocalDate.of(2026, 12, 1))
                .courtHearingLocation("B01LY01")
                .courtCentreId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .prosecutingAuthorityCode("EXAMPLE01")
                .offenceType("Council Tax")
                .caseCount(caseCount)
                .createdAt(Instant.parse("2026-11-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-11-01T00:00:00Z"))
                .build();
    }

    private static ProgressionGroupCaseResponse progressionResponse(final int memberCount) {
        final ProgressionCaseSummary leadCase = new ProgressionCaseSummary();
        leadCase.setCaseId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        leadCase.setCaseUrn("TESTURN0001");
        leadCase.setDefendantName("Example Defendant");

        final ProgressionGroupCaseResponse response = new ProgressionGroupCaseResponse();
        response.setGroupId(GROUP_ID);
        response.setLeadCase(leadCase);
        response.setCases(Collections.nCopies(memberCount, leadCase));
        return response;
    }

    @Test
    void create_new_group_should_save_the_mapped_entity() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);

        groupCaseService.create(request);

        verify(groupCaseRepository).saveAndFlush(any(GroupCaseEntity.class));
    }

    @Test
    void create_existing_group_should_throw_already_exists_and_not_save() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(true);

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(GroupCaseAlreadyExistsException.class)
                .hasMessageContaining(request.getGroupId().toString());
        verify(groupCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_with_unknown_parent_should_throw_not_found_and_not_save() {
        final UUID parentGroupId = UUID.randomUUID();
        final CreateGroupCaseRequest request = validRequest().parentGroupId(parentGroupId).build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.existsById(parentGroupId)).thenReturn(false);

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(GroupCaseNotFoundException.class)
                .hasMessageContaining(parentGroupId.toString());
        verify(groupCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_with_known_parent_should_save() {
        final UUID parentGroupId = UUID.randomUUID();
        final CreateGroupCaseRequest request = validRequest().parentGroupId(parentGroupId).build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.existsById(parentGroupId)).thenReturn(true);

        groupCaseService.create(request);

        verify(groupCaseRepository).saveAndFlush(any(GroupCaseEntity.class));
    }

    @Test
    void create_losing_a_race_on_the_primary_key_should_throw_already_exists() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.saveAndFlush(any(GroupCaseEntity.class)))
                .thenThrow(integrityViolation("pk_group_case"));

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(GroupCaseAlreadyExistsException.class);
    }

    private static DataIntegrityViolationException integrityViolation(final String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement",
                        new SQLException("constraint violated"), constraintName));
    }

    @Test
    void create_with_another_integrity_failure_should_not_be_reported_as_already_exists() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.saveAndFlush(any(GroupCaseEntity.class)))
                .thenThrow(integrityViolation("ck_group_case_case_count"));

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void getGroupCase_unknown_group_should_throw_not_found() {
        when(groupCaseRepository.findById(GROUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupCaseService.getGroupCase("cjscppuid", GROUP_ID, null))
                .isInstanceOf(GroupCaseNotFoundException.class)
                .hasMessageContaining(GROUP_ID.toString());
    }

    @Test
    void getGroupCase_matching_case_count_should_return_response_without_warning() {
        when(groupCaseRepository.findById(GROUP_ID)).thenReturn(Optional.of(storedGroupCase(1)));
        when(progressionClient.getGroupCaseMembers("cjscppuid", GROUP_ID)).thenReturn(progressionResponse(1));

        final GroupCaseResponse response = groupCaseService.getGroupCase("cjscppuid", GROUP_ID, null);

        assertThat(response.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(response.getCases()).hasSize(1);
        assertThat(response.getWarning()).isNull();
    }

    @Test
    void getGroupCase_mismatched_case_count_should_return_response_with_warning() {
        when(groupCaseRepository.findById(GROUP_ID)).thenReturn(Optional.of(storedGroupCase(4)));
        when(progressionClient.getGroupCaseMembers("cjscppuid", GROUP_ID)).thenReturn(progressionResponse(2));

        final GroupCaseResponse response = groupCaseService.getGroupCase("cjscppuid", GROUP_ID, null);

        assertThat(response.getWarning()).isEqualTo(GroupCaseResponse.WarningEnum.CASE_COUNT_MISMATCH);
        assertThat(response.getWarningDetails().getStoredCaseCount()).isEqualTo(4);
        assertThat(response.getWarningDetails().getActualCaseCount()).isEqualTo(2);
    }
}
