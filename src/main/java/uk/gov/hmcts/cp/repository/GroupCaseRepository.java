package uk.gov.hmcts.cp.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;

/**
 * Repository for civil bulk group records, searched by court centre (and optionally courtroom)
 * over a range of hearing dates. Date bounds are inclusive.
 */
public interface GroupCaseRepository extends JpaRepository<GroupCaseEntity, UUID> {

    Page<GroupCaseEntity> findByCourtCentreIdAndDateOfHearingBetween(
            UUID courtCentreId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    Page<GroupCaseEntity> findByCourtCentreIdAndCourtroomIdAndDateOfHearingBetween(
            UUID courtCentreId, UUID courtroomId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    /**
     * Finds a group and locks its row until the transaction ends, so concurrent changes to the
     * same group (such as two removals) run one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GroupCaseEntity> findWithLockByGroupId(UUID groupId);
}
