package uk.gov.hmcts.cp.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;
import uk.gov.hmcts.cp.config.TestContainersInitialise;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.entity.RemovedCaseEntity;

/**
 * Runs against a real Postgres so the removed_case migration, its keys and the Hibernate schema
 * validation are all exercised.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = TestContainersInitialise.class)
class RemovedCaseRepositoryTest {

    @Autowired
    private GroupCaseRepository groupCaseRepository;

    @Autowired
    private RemovedCaseRepository removedCaseRepository;

    @Test
    void shouldSaveRemovedCaseWithRemovedAtAndFindItByGroup() {
        final UUID groupId = savedGroup();
        final UUID caseId = UUID.randomUUID();

        removedCaseRepository.saveAndFlush(removedCase(caseId, groupId));

        final RemovedCaseEntity found = removedCaseRepository.findById(caseId).orElseThrow();
        assertThat(found.getGroupId()).isEqualTo(groupId);
        assertThat(found.getRemovedAt()).isNotNull();
        assertThat(removedCaseRepository.existsById(caseId)).isTrue();
        assertThat(removedCaseRepository.findByGroupId(groupId))
                .extracting(RemovedCaseEntity::getCaseId)
                .containsExactly(caseId);
    }

    @Test
    void shouldFindOnlyTheCasesRemovedFromTheGivenGroup() {
        final UUID groupId = savedGroup();
        final UUID otherGroupId = savedGroup();
        final UUID firstCaseId = UUID.randomUUID();
        final UUID secondCaseId = UUID.randomUUID();
        removedCaseRepository.saveAndFlush(removedCase(firstCaseId, groupId));
        removedCaseRepository.saveAndFlush(removedCase(secondCaseId, groupId));
        removedCaseRepository.saveAndFlush(removedCase(UUID.randomUUID(), otherGroupId));

        assertThat(removedCaseRepository.findByGroupId(groupId))
                .extracting(RemovedCaseEntity::getCaseId)
                .containsExactlyInAnyOrder(firstCaseId, secondCaseId);
    }

    @Test
    void shouldRejectRemovedCaseForUnknownGroup() {
        assertThatThrownBy(() -> removedCaseRepository.saveAndFlush(removedCase(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID savedGroup() {
        final GroupCaseEntity groupCase = GroupCaseEntity.builder()
                .groupId(UUID.randomUUID())
                .leadCaseId(UUID.randomUUID())
                .leadCaseUrn("TESTURN0001")
                .leadDefendantName("Defendant A")
                .dateOfHearing(LocalDate.of(2026, 12, 1))
                .courtHearingLocation("B01LY00")
                .courtCentreId(UUID.randomUUID())
                .prosecutingAuthorityCode("TESTPA")
                .offenceType("Council tax")
                .caseCount(3)
                .build();
        return groupCaseRepository.saveAndFlush(groupCase).getGroupId();
    }

    private static RemovedCaseEntity removedCase(final UUID caseId, final UUID groupId) {
        return RemovedCaseEntity.builder().caseId(caseId).groupId(groupId).build();
    }
}
