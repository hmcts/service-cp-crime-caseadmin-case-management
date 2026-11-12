package uk.gov.hmcts.cp.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.clients.ProgressionCaseSummary;
import uk.gov.hmcts.cp.clients.ProgressionClient;
import uk.gov.hmcts.cp.clients.ProgressionGroupCaseResponse;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.mappers.GroupCaseMapper;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;
import uk.gov.hmcts.cp.openapi.model.GroupCaseSearchResponse;
import uk.gov.hmcts.cp.repository.GroupCaseRepository;

@ExtendWith(MockitoExtension.class)
class GroupCaseServiceTest {

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COURT_CENTRE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID COURT_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final LocalDate START_DATE = LocalDate.of(2026, 12, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 12, 5);
    private static final Sort SEARCH_SORT = Sort.by("dateOfHearing", "leadDefendantName");

    @Mock
    private GroupCaseRepository groupCaseRepository;

    @Mock
    private ProgressionClient progressionClient;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

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

    @Test
    void search_without_courtroom_should_query_by_court_centre_and_date_range_sorted_by_date_then_name() {
        final Page<GroupCaseEntity> page = new PageImpl<>(List.of(storedGroupCase(1)));
        when(groupCaseRepository.findByCourtCentreIdAndDateOfHearingBetween(
                eq(COURT_CENTRE_ID), eq(START_DATE), eq(END_DATE), pageableCaptor.capture()))
                .thenReturn(page);

        final GroupCaseSearchResponse response =
                groupCaseService.search(COURT_CENTRE_ID, null, START_DATE, END_DATE, 1, 20);

        assertThat(response.getGroupCases()).hasSize(1);
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 20, SEARCH_SORT));
        verify(groupCaseRepository, never()).findByCourtCentreIdAndCourtroomIdAndDateOfHearingBetween(
                any(), any(), any(), any(), any());
    }

    @Test
    void search_with_courtroom_should_query_by_court_centre_and_courtroom() {
        final Page<GroupCaseEntity> page = new PageImpl<>(List.of(storedGroupCase(1)));
        when(groupCaseRepository.findByCourtCentreIdAndCourtroomIdAndDateOfHearingBetween(
                eq(COURT_CENTRE_ID), eq(COURT_ROOM_ID), eq(START_DATE), eq(END_DATE), pageableCaptor.capture()))
                .thenReturn(page);

        final GroupCaseSearchResponse response =
                groupCaseService.search(COURT_CENTRE_ID, COURT_ROOM_ID, START_DATE, END_DATE, 2, 10);

        assertThat(response.getGroupCases()).hasSize(1);
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(1, 10, SEARCH_SORT));
    }

    @Test
    void search_pageNumber_less_than_one_should_throw_bad_request() {
        assertThatThrownBy(() -> groupCaseService.search(COURT_CENTRE_ID, null, START_DATE, END_DATE, 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
        verify(groupCaseRepository, never()).findByCourtCentreIdAndDateOfHearingBetween(any(), any(), any(), any());
    }

    @Test
    void search_without_endDate_should_default_to_startDate() {
        when(groupCaseRepository.findByCourtCentreIdAndDateOfHearingBetween(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        groupCaseService.search(COURT_CENTRE_ID, null, START_DATE, null, 1, 20);

        verify(groupCaseRepository).findByCourtCentreIdAndDateOfHearingBetween(
                eq(COURT_CENTRE_ID), eq(START_DATE), eq(START_DATE), any());
    }
}
