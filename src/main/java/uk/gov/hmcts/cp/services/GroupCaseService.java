package uk.gov.hmcts.cp.services;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
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

/**
 * Creates and views group case records. Membership of the group stays in Progression; this
 * service only owns the group_case row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupCaseService {

    private static final String PRIMARY_KEY_CONSTRAINT = "pk_group_case";
    private static final int MIN_PAGE_NUMBER = 1;

    // leadDefendantName holds the defendant's full name with the given name first (e.g. "Jane
    // Doe"), so sorting on it also orders by first name; there is no separate first-name column.
    private static final Sort SEARCH_SORT = Sort.by("dateOfHearing", "leadDefendantName");

    private final GroupCaseRepository groupCaseRepository;
    private final GroupCaseMapper groupCaseMapper;
    private final ProgressionClient progressionClient;

    /**
     * Saves a new group case.
     *
     * @throws GroupCaseAlreadyExistsException if the groupId is already stored, including when a
     *         concurrent request inserts it first
     * @throws GroupCaseNotFoundException if a parentGroupId is given and does not exist
     */
    @Transactional
    public void create(final CreateGroupCaseRequest request) {
        if (groupCaseRepository.existsById(request.getGroupId())) {
            throw new GroupCaseAlreadyExistsException(request.getGroupId());
        }
        if (request.getParentGroupId() != null && !groupCaseRepository.existsById(request.getParentGroupId())) {
            throw new GroupCaseNotFoundException(request.getParentGroupId());
        }
        try {
            groupCaseRepository.saveAndFlush(groupCaseMapper.toEntity(request));
        } catch (DataIntegrityViolationException exception) {
            if (isPrimaryKeyViolation(exception)) {
                throw new GroupCaseAlreadyExistsException(request.getGroupId());
            }
            throw exception;
        }
        log.info("Group case {} created", request.getGroupId());
    }

    /**
     * Returns the group case view, aggregating its current active member cases from Progression.
     *
     * @throws GroupCaseNotFoundException if no group case with this groupId is stored
     */
    public GroupCaseResponse getGroupCase(final String cjsCppUid, final UUID groupId, final UUID referredCaseId) {
        final GroupCaseEntity entity = groupCaseRepository.findById(groupId)
                .orElseThrow(() -> new GroupCaseNotFoundException(groupId));
        final ProgressionGroupCaseResponse progressionResponse =
                progressionClient.getGroupCaseMembers(cjsCppUid, groupId);

        final int actualCaseCount = progressionResponse.getCases().size();
        if (!entity.getCaseCount().equals(actualCaseCount)) {
            log.warn("Group case count mismatch: groupId={}, stored={}, actual={}",
                    groupId, entity.getCaseCount(), actualCaseCount);
        }

        return groupCaseMapper.toResponse(entity, progressionResponse, referredCaseId);
    }

    /**
     * Searches group cases by court centre (and optional courtroom) over a hearing date range,
     * sorted by hearing date ascending, then by defendant name ascending.
     */
    public GroupCaseSearchResponse search(final UUID courtCentreId, final UUID courtRoomId,
            final LocalDate startDate, final LocalDate endDate, final int pageNumber, final int pageSize) {

        if (pageNumber < MIN_PAGE_NUMBER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageNumber must be at least " + MIN_PAGE_NUMBER);
        }
        final LocalDate effectiveEndDate = endDate == null ? startDate : endDate;
        final Pageable pageable = PageRequest.of(pageNumber - 1, pageSize, SEARCH_SORT);
        final Page<GroupCaseEntity> page = courtRoomId == null
                ? groupCaseRepository.findByCourtCentreIdAndDateOfHearingBetween(
                        courtCentreId, startDate, effectiveEndDate, pageable)
                : groupCaseRepository.findByCourtCentreIdAndCourtroomIdAndDateOfHearingBetween(
                        courtCentreId, courtRoomId, startDate, effectiveEndDate, pageable);
        return groupCaseMapper.toSearchResponse(page);
    }

    private static boolean isPrimaryKeyViolation(final DataIntegrityViolationException exception) {
        return exception.getCause() instanceof ConstraintViolationException violation
                && PRIMARY_KEY_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
    }
}
