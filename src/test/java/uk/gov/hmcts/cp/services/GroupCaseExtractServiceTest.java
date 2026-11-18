package uk.gov.hmcts.cp.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.clients.ProgressionClient;
import uk.gov.hmcts.cp.config.CaseManagementSystemUser;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;
import uk.gov.hmcts.cp.openapi.model.ExtractCasesRequest;
import uk.gov.hmcts.cp.openapi.model.ExtractLeadCase;
import uk.gov.hmcts.cp.openapi.model.ResultingIntent;
import uk.gov.hmcts.cp.repository.GroupCaseRepository;

@ExtendWith(MockitoExtension.class)
class GroupCaseExtractServiceTest {

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEAD_CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER_CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID NEW_LEAD_CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-0000000000aa";

    @Mock
    private GroupCaseRepository groupCaseRepository;

    @Mock
    private ProgressionClient progressionClient;

    @Mock
    private CaseManagementSystemUser systemUser;

    private GroupCaseExtractService service;

    @BeforeEach
    void setUp() {
        service = new GroupCaseExtractService(groupCaseRepository, progressionClient, systemUser);
    }

    private static GroupCaseEntity storedGroupCase(final int caseCount) {
        return GroupCaseEntity.builder()
                .groupId(GROUP_ID)
                .leadCaseId(LEAD_CASE_ID)
                .leadCaseUrn("TESTURN0001")
                .leadDefendantName("Example Defendant")
                .dateOfHearing(LocalDate.of(2026, 12, 1))
                .courtHearingLocation("B01LY01")
                .courtCentreId(UUID.randomUUID())
                .prosecutingAuthorityCode("EXAMPLE01")
                .offenceType("Council Tax")
                .caseCount(caseCount)
                .build();
    }

    private static ExtractCasesRequest.ExtractCasesRequestBuilder removeOne(final UUID caseId) {
        return ExtractCasesRequest.builder()
                .caseIds(List.of(caseId))
                .newGroup(false)
                .resultingIntent(ResultingIntent.GRANT);
    }

    private static ExtractLeadCase newLead() {
        return ExtractLeadCase.builder()
                .caseId(NEW_LEAD_CASE_ID)
                .caseUrn("TESTURN0004")
                .defendantName("Another Defendant")
                .build();
    }

    private GroupCaseEntity givenStoredGroup(final int caseCount) {
        final GroupCaseEntity entity = storedGroupCase(caseCount);
        when(groupCaseRepository.findWithLockByGroupId(GROUP_ID)).thenReturn(Optional.of(entity));
        return entity;
    }

    private void assertBadRequest(final ExtractCasesRequest request, final String messagePart) {
        assertThatThrownBy(() -> service.extractCases(GROUP_ID, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains(messagePart);
                });
        verifyNoInteractions(progressionClient);
        verify(groupCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void removing_a_member_case_should_send_the_remove_command_and_reduce_the_case_count() {
        final GroupCaseEntity entity = givenStoredGroup(5);
        when(systemUser.getSystemUserId()).thenReturn(SYSTEM_USER_ID);

        service.extractCases(GROUP_ID, removeOne(MEMBER_CASE_ID).build());

        verify(progressionClient).removeCaseFromGroup(SYSTEM_USER_ID, MEMBER_CASE_ID, GROUP_ID, null);
        verify(groupCaseRepository).saveAndFlush(entity);
        assertThat(entity.getCaseCount()).isEqualTo(4);
        assertThat(entity.getLeadCaseId()).isEqualTo(LEAD_CASE_ID);
        assertThat(entity.getLeadCaseUrn()).isEqualTo("TESTURN0001");
        assertThat(entity.getLeadDefendantName()).isEqualTo("Example Defendant");
    }

    @Test
    void removing_the_lead_case_should_send_the_new_lead_and_store_it_as_the_group_lead() {
        final GroupCaseEntity entity = givenStoredGroup(3);
        when(systemUser.getSystemUserId()).thenReturn(SYSTEM_USER_ID);

        service.extractCases(GROUP_ID, removeOne(LEAD_CASE_ID).leadCase(newLead()).build());

        verify(progressionClient).removeCaseFromGroup(SYSTEM_USER_ID, LEAD_CASE_ID, GROUP_ID, NEW_LEAD_CASE_ID);
        verify(groupCaseRepository).saveAndFlush(entity);
        assertThat(entity.getCaseCount()).isEqualTo(2);
        assertThat(entity.getLeadCaseId()).isEqualTo(NEW_LEAD_CASE_ID);
        assertThat(entity.getLeadCaseUrn()).isEqualTo("TESTURN0004");
        assertThat(entity.getLeadDefendantName()).isEqualTo("Another Defendant");
    }

    @Test
    void resulting_intent_should_be_ignored() {
        final GroupCaseEntity entity = givenStoredGroup(4);
        when(systemUser.getSystemUserId()).thenReturn(SYSTEM_USER_ID);

        service.extractCases(GROUP_ID, removeOne(MEMBER_CASE_ID).resultingIntent(ResultingIntent.WITHDRAW).build());

        assertThat(entity.getResultingIntent()).isEqualTo(uk.gov.hmcts.cp.entity.ResultingIntent.GRANT);
        assertThat(entity.getCaseCount()).isEqualTo(3);
    }

    @Test
    void new_group_should_be_rejected_as_not_supported() {
        assertBadRequest(removeOne(MEMBER_CASE_ID).newGroup(true).build(), "new group");
        verify(groupCaseRepository, never()).findWithLockByGroupId(any());
    }

    @Test
    void more_than_one_case_should_be_rejected() {
        assertBadRequest(removeOne(MEMBER_CASE_ID).caseIds(List.of(MEMBER_CASE_ID, NEW_LEAD_CASE_ID)).build(),
                "one case");
        verify(groupCaseRepository, never()).findWithLockByGroupId(any());
    }

    @Test
    void unknown_group_should_throw_not_found() {
        when(groupCaseRepository.findWithLockByGroupId(GROUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.extractCases(GROUP_ID, removeOne(MEMBER_CASE_ID).build()))
                .isInstanceOf(GroupCaseNotFoundException.class);
        verifyNoInteractions(progressionClient);
    }

    @Test
    void group_with_fewer_than_two_cases_should_be_rejected() {
        givenStoredGroup(1);

        assertBadRequest(removeOne(MEMBER_CASE_ID).build(), "at least 2 cases");
    }

    @Test
    void group_with_two_cases_should_allow_a_removal_leaving_one() {
        final GroupCaseEntity entity = givenStoredGroup(2);
        when(systemUser.getSystemUserId()).thenReturn(SYSTEM_USER_ID);

        service.extractCases(GROUP_ID, removeOne(MEMBER_CASE_ID).build());

        verify(groupCaseRepository).saveAndFlush(entity);
        assertThat(entity.getCaseCount()).isEqualTo(1);
    }

    @Test
    void removing_the_lead_without_a_new_lead_should_be_rejected() {
        givenStoredGroup(5);

        assertBadRequest(removeOne(LEAD_CASE_ID).build(), "leadCase is required");
    }

    @Test
    void new_lead_when_the_lead_is_not_removed_should_be_rejected() {
        givenStoredGroup(5);

        assertBadRequest(removeOne(MEMBER_CASE_ID).leadCase(newLead()).build(), "leadCase must not be supplied");
    }

    @Test
    void new_lead_that_is_the_removed_case_should_be_rejected() {
        givenStoredGroup(5);
        final ExtractLeadCase sameAsRemoved = ExtractLeadCase.builder()
                .caseId(LEAD_CASE_ID).caseUrn("TESTURN0001").defendantName("Example Defendant").build();

        assertBadRequest(removeOne(LEAD_CASE_ID).leadCase(sameAsRemoved).build(), "stays in the group");
    }

    @Test
    void missing_system_user_should_fail_without_calling_progression_or_saving() {
        givenStoredGroup(5);
        when(systemUser.getSystemUserId()).thenThrow(new SystemUserNotConfiguredException());

        assertThatThrownBy(() -> service.extractCases(GROUP_ID, removeOne(MEMBER_CASE_ID).build()))
                .isInstanceOf(SystemUserNotConfiguredException.class);
        verifyNoInteractions(progressionClient);
        verify(groupCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void progression_not_accepting_the_command_should_leave_the_group_unchanged() {
        final GroupCaseEntity entity = givenStoredGroup(5);
        when(systemUser.getSystemUserId()).thenReturn(SYSTEM_USER_ID);
        doThrow(new ProgressionCommandRejectedException(HttpStatusCode.valueOf(403)))
                .when(progressionClient).removeCaseFromGroup(eq(SYSTEM_USER_ID), eq(MEMBER_CASE_ID), eq(GROUP_ID), isNull());

        assertThatThrownBy(() -> service.extractCases(GROUP_ID, removeOne(MEMBER_CASE_ID).build()))
                .isInstanceOf(ProgressionCommandRejectedException.class);
        verify(groupCaseRepository, never()).saveAndFlush(any());
        assertThat(entity.getCaseCount()).isEqualTo(5);
        assertThat(entity.getLeadCaseId()).isEqualTo(LEAD_CASE_ID);
    }
}
