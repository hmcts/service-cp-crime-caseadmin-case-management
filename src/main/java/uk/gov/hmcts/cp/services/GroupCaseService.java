package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.mappers.GroupCaseMapper;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.repository.GroupCaseRepository;

/**
 * Creates group case records. Membership of the group stays in Progression; this service only
 * owns the group_case row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupCaseService {

    private static final String PRIMARY_KEY_CONSTRAINT = "pk_group_case";

    private final GroupCaseRepository groupCaseRepository;
    private final GroupCaseMapper groupCaseMapper;

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

    private static boolean isPrimaryKeyViolation(final DataIntegrityViolationException exception) {
        return exception.getCause() instanceof ConstraintViolationException violation
                && PRIMARY_KEY_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
    }
}
