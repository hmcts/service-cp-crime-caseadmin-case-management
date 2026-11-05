package uk.gov.hmcts.cp.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.openapi.api.GroupCasesApi;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.services.GroupCaseService;

/**
 * Exposes the group case endpoints defined by the generated OpenAPI contract. Operations not
 * overridden here still return the generated 501 Not Implemented.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class GroupCaseController implements GroupCasesApi {

    private final GroupCaseService groupCaseService;

    /**
     * Creates a group case. Called by prosecution-casefile when a group submission is received.
     *
     * @param cjsCppUid authenticated user identifier from the request header
     * @param createGroupCaseRequest group case to create, already bean-validated
     * @param cppClientCorrelationId optional correlation identifier supplied by the client
     * @return HTTP 200 with no body
     */
    @Override
    public ResponseEntity<Void> createGroupCase(
            final String cjsCppUid,
            final CreateGroupCaseRequest createGroupCaseRequest,
            @Nullable final String cppClientCorrelationId) {

        log.info("Create group case request received for group {}", createGroupCaseRequest.getGroupId());
        groupCaseService.create(createGroupCaseRequest);
        return ResponseEntity.ok().build();
    }
}
