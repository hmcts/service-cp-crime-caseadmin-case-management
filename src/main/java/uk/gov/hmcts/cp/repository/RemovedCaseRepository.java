package uk.gov.hmcts.cp.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.cp.entity.RemovedCaseEntity;

/**
 * Repository for cases removed from a group as individual cases, keyed by case id.
 */
public interface RemovedCaseRepository extends JpaRepository<RemovedCaseEntity, UUID> {

    List<RemovedCaseEntity> findByGroupId(UUID groupId);
}
