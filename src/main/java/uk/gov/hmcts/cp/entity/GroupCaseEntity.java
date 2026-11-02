package uk.gov.hmcts.cp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A civil bulk (group) case. Only the group record is held here; its member cases stay in
 * Progression, keyed by the same group id.
 */
@Entity
@Table(name = "group_case")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupCaseEntity {

    // assigned by the caller: the group id Progression already uses
    @Id
    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    // set when this group was split from another
    @Column(name = "parent_group_id")
    private UUID parentGroupId;

    @Column(name = "lead_case_id", nullable = false)
    private UUID leadCaseId;

    @Column(name = "lead_case_urn", nullable = false)
    private String leadCaseUrn;

    @Column(name = "lead_defendant_name", nullable = false)
    private String leadDefendantName;

    @Column(name = "lead_defendant_dob")
    private LocalDate leadDefendantDob;

    @Column(name = "date_of_hearing", nullable = false)
    private LocalDate dateOfHearing;

    // OU code: 5-char court house + 2-char courtroom
    @Column(name = "court_hearing_location", nullable = false, length = 7)
    private String courtHearingLocation;

    @Column(name = "court_centre_id", nullable = false)
    private UUID courtCentreId;

    @Column(name = "courtroom_id")
    private UUID courtroomId;

    @Column(name = "prosecuting_authority_code", nullable = false, length = 10)
    private String prosecutingAuthorityCode;

    @Column(name = "prosecuting_authority_name")
    private String prosecutingAuthorityName;

    @Column(name = "offence_type", nullable = false, length = 250)
    private String offenceType;

    @Column(name = "case_count", nullable = false)
    private Integer caseCount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_intent", nullable = false, length = 20)
    private ResultingIntent resultingIntent = ResultingIntent.GRANT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        final Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
