package uk.gov.hmcts.cp.clients;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Client for Progression's group-cases query, used to look up a group's current member cases. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressionClient {

    public static final String GROUP_CASES_PATH =
            "/progression-query-api/query/api/rest/progression/prosecutioncases/group/{groupId}";

    private static final MediaType GROUP_CASES_MEDIA_TYPE =
            MediaType.valueOf("application/vnd.progression.query.group-cases+json");

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
}
