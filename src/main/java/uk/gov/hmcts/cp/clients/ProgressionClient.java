package uk.gov.hmcts.cp.clients;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;

/**
 * Client for Progression: the group-cases query, used to look up a group's current member cases,
 * and the remove-case-from-group-cases command.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressionClient {

    public static final String GROUP_CASES_PATH =
            "/progression-query-api/query/api/rest/progression/prosecutioncases/group/{groupId}";

    private static final MediaType GROUP_CASES_MEDIA_TYPE =
            MediaType.valueOf("application/vnd.progression.query.group-cases+json");

    public static final String REMOVE_CASE_FROM_GROUP_PATH =
            "/progression-command-api/command/api/rest/progression/prosecutioncases/{caseId}";

    private static final MediaType REMOVE_CASE_FROM_GROUP_MEDIA_TYPE =
            MediaType.valueOf("application/vnd.progression.remove-case-from-group-cases+json");

    private final RestClient restClient;

    public ProgressionGroupCaseResponse getGroupCaseMembers(final String cjsCppUid, final UUID groupId) {
        log.info("Fetching group case members from Progression for group {}", groupId);
        return restClient.get()
                .uri(GROUP_CASES_PATH, groupId)
                .header("CJSCPPUID", cjsCppUid)
                .accept(GROUP_CASES_MEDIA_TYPE)
                .retrieve()
                .body(ProgressionGroupCaseResponse.class);
    }

    /**
     * Sends Progression's remove-case-from-group-cases command. Progression handles it
     * asynchronously; only a 202 means it was accepted.
     *
     * @param systemUserId the system user the command is sent as
     * @param caseId the case to remove from the group
     * @param groupId the group the case is removed from
     * @param newLeadCaseId the new lead when the lead case is removed, otherwise null
     * @throws ProgressionCommandRejectedException if Progression does not reply 202 or cannot be reached
     */
    public void removeCaseFromGroup(final String systemUserId, final UUID caseId, final UUID groupId,
            final UUID newLeadCaseId) {
        log.info("Sending remove-case-from-group-cases to Progression for group {}", groupId);
        final HttpStatusCode status;
        try {
            status = restClient.post()
                    .uri(REMOVE_CASE_FROM_GROUP_PATH, caseId)
                    .header("CJSCPPUID", systemUserId)
                    .contentType(REMOVE_CASE_FROM_GROUP_MEDIA_TYPE)
                    .body(new RemoveCaseFromGroupCommand(groupId, newLeadCaseId))
                    .exchange((request, response) -> response.getStatusCode());
        } catch (RestClientException exception) {
            throw new ProgressionCommandRejectedException(exception);
        }
        if (!HttpStatus.ACCEPTED.isSameCodeAs(status)) {
            throw new ProgressionCommandRejectedException(status);
        }
    }
}
