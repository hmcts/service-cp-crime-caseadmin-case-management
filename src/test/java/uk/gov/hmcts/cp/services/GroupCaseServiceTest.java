package uk.gov.hmcts.cp.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.validRequest;

import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.hmcts.cp.entity.GroupCaseEntity;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.mappers.GroupCaseMapper;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.repository.GroupCaseRepository;

@ExtendWith(MockitoExtension.class)
class GroupCaseServiceTest {

    @Mock
    private GroupCaseRepository groupCaseRepository;

    private GroupCaseService groupCaseService;

    @BeforeEach
    void setUp() {
        groupCaseService = new GroupCaseService(groupCaseRepository, new GroupCaseMapper());
    }

    @Test
    void create_new_group_should_save_the_mapped_entity() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);

        groupCaseService.create(request);

        verify(groupCaseRepository).saveAndFlush(any(GroupCaseEntity.class));
    }

    @Test
    void create_existing_group_should_throw_already_exists_and_not_save() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(true);

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(GroupCaseAlreadyExistsException.class)
                .hasMessageContaining(request.getGroupId().toString());
        verify(groupCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_with_unknown_parent_should_throw_not_found_and_not_save() {
        final UUID parentGroupId = UUID.randomUUID();
        final CreateGroupCaseRequest request = validRequest().parentGroupId(parentGroupId).build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.existsById(parentGroupId)).thenReturn(false);

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(GroupCaseNotFoundException.class)
                .hasMessageContaining(parentGroupId.toString());
        verify(groupCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_with_known_parent_should_save() {
        final UUID parentGroupId = UUID.randomUUID();
        final CreateGroupCaseRequest request = validRequest().parentGroupId(parentGroupId).build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.existsById(parentGroupId)).thenReturn(true);

        groupCaseService.create(request);

        verify(groupCaseRepository).saveAndFlush(any(GroupCaseEntity.class));
    }

    @Test
    void create_losing_a_race_on_the_primary_key_should_throw_already_exists() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.saveAndFlush(any(GroupCaseEntity.class)))
                .thenThrow(integrityViolation("pk_group_case"));

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(GroupCaseAlreadyExistsException.class);
    }

    private static DataIntegrityViolationException integrityViolation(final String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement",
                        new SQLException("constraint violated"), constraintName));
    }

    @Test
    void create_with_another_integrity_failure_should_not_be_reported_as_already_exists() {
        final CreateGroupCaseRequest request = validRequest().build();
        when(groupCaseRepository.existsById(request.getGroupId())).thenReturn(false);
        when(groupCaseRepository.saveAndFlush(any(GroupCaseEntity.class)))
                .thenThrow(integrityViolation("ck_group_case_case_count"));

        assertThatThrownBy(() -> groupCaseService.create(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
