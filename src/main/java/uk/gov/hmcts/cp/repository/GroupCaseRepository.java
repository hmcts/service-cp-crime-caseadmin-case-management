package uk.gov.hmcts.cp.repository;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
