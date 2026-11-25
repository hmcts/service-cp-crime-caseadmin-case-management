package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A case removed from a group as an individual case (CAD-1859). A case can be removed from a
 * group only once, so the case id is the key.
 */
@Entity
@Table(name = "removed_case")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemovedCaseEntity {

    // Progression case id of the removed case
    @Id
    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    // group the case was removed from
    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "removed_at", nullable = false, updatable = false)
    private Instant removedAt;

    @PrePersist
    protected void onCreate() {
        removedAt = Instant.now();
    }
}
