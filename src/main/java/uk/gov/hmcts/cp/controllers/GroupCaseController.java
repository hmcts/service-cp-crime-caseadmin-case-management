package uk.gov.hmcts.cp.controllers;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.openapi.api.GroupCasesApi;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;
import uk.gov.hmcts.cp.openapi.model.GroupCaseSearchResponse;
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

    /**
     * Views a group case with its current active member cases.
     *
     * @param cjsCppUid authenticated user identifier from the request header
     * @param groupId the group case to view
     * @param cppClientCorrelationId optional correlation identifier supplied by the client
     * @param referredCaseId optional id of the member case the page was accessed from
     * @return HTTP 200 with the group case view
     */
    @Override
    public ResponseEntity<GroupCaseResponse> getGroupCase(
            final String cjsCppUid,
            final UUID groupId,
            @Nullable final String cppClientCorrelationId,
            @Nullable final UUID referredCaseId) {

        log.info("Get group case request received for group {}", groupId);
        return ResponseEntity.ok(groupCaseService.getGroupCase(cjsCppUid, groupId, referredCaseId));
    }

    /**
     * Searches group cases by court centre and hearing date range, sorted by hearing date then
     * defendant name.
     *
     * @param cjsCppUid authenticated user identifier from the request header
     * @param courtCentreId court centre to search hearings at
     * @param startDate start of the hearing date range (inclusive)
     * @param cppClientCorrelationId optional correlation identifier supplied by the client
     * @param courtRoomId optional courtroom to narrow the search to, within courtCentreId
     * @param endDate optional end of the hearing date range (inclusive); defaults to startDate
     * @param pageNumber one-based page index
     * @param pageSize number of results per page
     * @return HTTP 200 with a page of matching group case summaries
     */
    @Override
    public ResponseEntity<GroupCaseSearchResponse> searchGroupCases(
            final String cjsCppUid,
            final UUID courtCentreId,
            final LocalDate startDate,
            @Nullable final String cppClientCorrelationId,
            @Nullable final UUID courtRoomId,
            @Nullable final LocalDate endDate,
            final Integer pageNumber,
            final Integer pageSize) {

        log.info("Search group cases request received for court centre {}", courtCentreId);
        return ResponseEntity.ok(groupCaseService.search(
                courtCentreId, courtRoomId, startDate, endDate, pageNumber, pageSize));
    }
}
