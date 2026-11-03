package uk.gov.hmcts.cp.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ContextConfiguration;
import uk.gov.hmcts.cp.config.TestContainersInitialise;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.entity.ResultingIntent;

/**
 * Runs against a real Postgres so the Flyway migration, its constraints and the Hibernate schema
 * validation are all exercised.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = TestContainersInitialise.class)
class GroupCaseRepositoryTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 11, 2);
    private static final LocalDate END_DATE = LocalDate.of(2026, 11, 6);
    private static final Sort SEARCH_SORT = Sort.by("dateOfHearing", "leadDefendantName");

    @Autowired
    private GroupCaseRepository groupCaseRepository;

    @Test
    void shouldSaveAndFindGroupById() {
        final UUID courtCentreId = UUID.randomUUID();
        final GroupCaseEntity groupCase = groupCase(courtCentreId, null, START_DATE, "Defendant A");

        groupCaseRepository.saveAndFlush(groupCase);

        final GroupCaseEntity found = groupCaseRepository.findById(groupCase.getGroupId()).orElseThrow();
        assertThat(found.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(found.getLeadDefendantName()).isEqualTo("Defendant A");
        assertThat(found.getDateOfHearing()).isEqualTo(START_DATE);
        assertThat(found.getCaseCount()).isEqualTo(3);
    }

    @Test
    void shouldDefaultResultingIntentToGrantAndSetTimestampsOnPersist() {
        final GroupCaseEntity groupCase = groupCase(UUID.randomUUID(), null, START_DATE, "Defendant A");

        final GroupCaseEntity saved = groupCaseRepository.saveAndFlush(groupCase);

        assertThat(saved.getResultingIntent()).isEqualTo(ResultingIntent.GRANT);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldSearchByCourtCentreWithinDateRangeAndPage() {
        final UUID courtCentreId = UUID.randomUUID();
        final GroupCaseEntity first = groupCase(courtCentreId, null, START_DATE, "Defendant B");
        final GroupCaseEntity second = groupCase(courtCentreId, null, START_DATE, "Defendant C");
        final GroupCaseEntity third = groupCase(courtCentreId, null, END_DATE, "Defendant A");
        groupCaseRepository.saveAllAndFlush(List.of(third, second, first,
                groupCase(UUID.randomUUID(), null, START_DATE, "Other centre"),
                groupCase(courtCentreId, null, START_DATE.minusDays(1), "Before range"),
                groupCase(courtCentreId, null, END_DATE.plusDays(1), "After range")));

        final Page<GroupCaseEntity> firstPage = groupCaseRepository.findByCourtCentreIdAndDateOfHearingBetween(
                courtCentreId, START_DATE, END_DATE, PageRequest.of(0, 2, SEARCH_SORT));
        final Page<GroupCaseEntity> secondPage = groupCaseRepository.findByCourtCentreIdAndDateOfHearingBetween(
                courtCentreId, START_DATE, END_DATE, PageRequest.of(1, 2, SEARCH_SORT));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(GroupCaseEntity::getGroupId)
                .containsExactly(first.getGroupId(), second.getGroupId());
        assertThat(secondPage.getContent()).extracting(GroupCaseEntity::getGroupId)
                .containsExactly(third.getGroupId());
    }

    @Test
    void shouldSearchByCourtCentreAndCourtroom() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtroomId = UUID.randomUUID();
        final GroupCaseEntity inCourtroom = groupCase(courtCentreId, courtroomId, START_DATE, "Defendant A");
        groupCaseRepository.saveAllAndFlush(List.of(inCourtroom,
                groupCase(courtCentreId, UUID.randomUUID(), START_DATE, "Other courtroom"),
                groupCase(courtCentreId, null, START_DATE, "No courtroom")));

        final Page<GroupCaseEntity> result = groupCaseRepository.findByCourtCentreIdAndCourtroomIdAndDateOfHearingBetween(
                courtCentreId, courtroomId, START_DATE, START_DATE, PageRequest.of(0, 200, SEARCH_SORT));

        assertThat(result.getContent()).extracting(GroupCaseEntity::getGroupId)
                .containsExactly(inCourtroom.getGroupId());
    }

    @Test
    void shouldRejectGroupWithNoCases() {
        final GroupCaseEntity groupCase = groupCase(UUID.randomUUID(), null, START_DATE, "Defendant A");
        groupCase.setCaseCount(0);

        assertThatThrownBy(() -> groupCaseRepository.saveAndFlush(groupCase))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectChildGroupWhoseParentDoesNotExist() {
        final GroupCaseEntity groupCase = groupCase(UUID.randomUUID(), null, START_DATE, "Defendant A");
        groupCase.setParentGroupId(UUID.randomUUID());

        assertThatThrownBy(() -> groupCaseRepository.saveAndFlush(groupCase))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldStoreUrnAndOffenceTypeLongerThanTheirPreviousLimits() {
        final GroupCaseEntity groupCase = groupCase(UUID.randomUUID(), null, START_DATE, "Defendant A");
        groupCase.setLeadCaseUrn("U".repeat(255));
        groupCase.setOffenceType("O".repeat(250));

        groupCaseRepository.saveAndFlush(groupCase);

        final GroupCaseEntity found = groupCaseRepository.findById(groupCase.getGroupId()).orElseThrow();
        assertThat(found.getLeadCaseUrn()).hasSize(255);
        assertThat(found.getOffenceType()).hasSize(250);
    }

    @Test
    void shouldRejectProsecutingAuthorityCodeLongerThanAnOuCode() {
        final GroupCaseEntity groupCase = groupCase(UUID.randomUUID(), null, START_DATE, "Defendant A");
        groupCase.setProsecutingAuthorityCode("P".repeat(11));

        assertThatThrownBy(() -> groupCaseRepository.saveAndFlush(groupCase))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static GroupCaseEntity groupCase(final UUID courtCentreId, final UUID courtroomId,
                                             final LocalDate dateOfHearing, final String leadDefendantName) {
        return GroupCaseEntity.builder()
                .groupId(UUID.randomUUID())
                .leadCaseId(UUID.randomUUID())
                .leadCaseUrn("TESTURN0001")
                .leadDefendantName(leadDefendantName)
                .dateOfHearing(dateOfHearing)
                .courtHearingLocation("B01LY00")
                .courtCentreId(courtCentreId)
                .courtroomId(courtroomId)
                .prosecutingAuthorityCode("TESTPA")
                .offenceType("Council tax")
                .caseCount(3)
                .build();
    }
}
