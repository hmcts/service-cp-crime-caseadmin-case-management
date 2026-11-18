package uk.gov.hmcts.cp.services;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.clients.ProgressionClient;
import uk.gov.hmcts.cp.config.CaseManagementSystemUser;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.openapi.model.ExtractCasesRequest;
import uk.gov.hmcts.cp.openapi.model.ExtractLeadCase;
import uk.gov.hmcts.cp.repository.GroupCaseRepository;

/**
 * Removes a case from a group (CAD-1860). Only one case at a time, as an individual case
 * (newGroup=false). Checks use case management's own group_case row; Progression is not queried.
 * The group is changed only after Progression accepts the remove command with 202. If Progression
 * later fails to process it, the stored case count no longer matches Progression and the group
 * case view shows the count-mismatch warning.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupCaseExtractService {

    // a case can only be removed from a group that has at least 2 cases, so at least 1 remains
    private static final int MIN_CASES_BEFORE_REMOVAL = 2;

    // CAD-1860 removes one case per request
    private static final int MAX_CASES_PER_REMOVAL = 1;

    private final GroupCaseRepository groupCaseRepository;
    private final ProgressionClient progressionClient;
    private final CaseManagementSystemUser systemUser;

    /**
     * Removes the single case in the request from the group. resultingIntent is ignored.
     *
     * @throws ResponseStatusException with 400 if the request breaks a removal rule
     * @throws GroupCaseNotFoundException if the group does not exist
     * @throws uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException if no system user is configured
     * @throws uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException if Progression does not reply 202
     */
    @Transactional
    public void extractCases(final UUID groupId, final ExtractCasesRequest request) {
        if (Boolean.TRUE.equals(request.getNewGroup())) {
            throw badRequest("Moving cases to a new group is not supported yet");
        }
        if (request.getCaseIds().size() > MAX_CASES_PER_REMOVAL) {
            throw badRequest("Only one case can be removed at a time");
        }
        final UUID caseId = request.getCaseIds().getFirst();

        final GroupCaseEntity group = groupCaseRepository.findWithLockByGroupId(groupId)
                .orElseThrow(() -> new GroupCaseNotFoundException(groupId));
        if (group.getCaseCount() < MIN_CASES_BEFORE_REMOVAL) {
            throw badRequest("The group must have at least 2 cases to remove one");
        }

        final boolean leadRemoved = caseId.equals(group.getLeadCaseId());
        final ExtractLeadCase newLead = request.getLeadCase();
        if (leadRemoved && newLead == null) {
            throw badRequest("leadCase is required when the lead case is removed");
        }
        if (!leadRemoved && newLead != null) {
            throw badRequest("leadCase must not be supplied when the lead case is not removed");
        }
        if (newLead != null && caseId.equals(newLead.getCaseId())) {
            throw badRequest("leadCase must be a case that stays in the group");
        }

        progressionClient.removeCaseFromGroup(systemUser.getSystemUserId(), caseId, groupId,
                leadRemoved ? newLead.getCaseId() : null);

        group.setCaseCount(group.getCaseCount() - 1);
        if (leadRemoved) {
            group.setLeadCaseId(newLead.getCaseId());
            group.setLeadCaseUrn(newLead.getCaseUrn());
            group.setLeadDefendantName(newLead.getDefendantName());
        }
        groupCaseRepository.saveAndFlush(group);
        log.info("Case removed from group {}: Progression accepted the command, lead changed: {}, case count now {}",
                groupId, leadRemoved, group.getCaseCount());
    }

    private static ResponseStatusException badRequest(final String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
