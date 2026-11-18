package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Black-box test of POST /groupcases/{groupId}/extractcases against the dockerised app, its
 * Postgres and the WireMock stand-in for Progression. The app calls Progression's
 * remove-case-from-group-cases command as its configured system user
 * (CASEMANAGEMENT_SYSTEM_USER_ID in docker-compose.integration.yml); WireMock accepts it with 202,
 * except for the case id in progression-remove-case-rejected.json, which gets a 500.
 */
public class ExtractGroupCasesHttpLiveTest extends AbstractTest {

    private static final String SYSTEM_USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String UNAUTHORIZED_CJSCPPUID = "aaaaaaaa-0000-0000-0000-000000000003";
    private static final UUID REJECTED_CASE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String HEARING_DATE = "2026-12-01";
    private static final String REMOVE_PATH =
            "/progression-command-api/command/api/rest/progression/prosecutioncases/";

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casemanagement-service");
    private final RestTemplate http = new RestTemplate();

    ExtractGroupCasesHttpLiveTest() {
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(final ClientHttpResponse response) {
                return false;
            }
        });
    }

    private record StoredGroup(UUID groupId, UUID leadCaseId, UUID courtCentreId) {
    }

    private StoredGroup createGroup(final int caseCount) {
        final StoredGroup group = new StoredGroup(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        final Map<String, Object> leadCase = new HashMap<>();
        leadCase.put("leadCaseId", group.leadCaseId().toString());
        leadCase.put("caseUrn", "TESTURN0001");
        leadCase.put("defendantName", "Example Defendant");

        final Map<String, Object> body = new HashMap<>();
        body.put("groupId", group.groupId().toString());
        body.put("leadCase", leadCase);
        body.put("dateOfHearing", HEARING_DATE);
        body.put("caseCount", caseCount);
        body.put("offenceType", "Council Tax");
        body.put("courtHearingLocation", "B01LY01");
        body.put("courtCentreId", group.courtCentreId().toString());
        body.put("prosecutingAuthority", "EXAMPLE01");

        final ResponseEntity<String> res = http.exchange(baseUrl + "/groupcases", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(UUID.randomUUID().toString())), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return group;
    }

    private static HttpHeaders jsonHeaders(final String cjsCppUid) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("CJSCPPUID", cjsCppUid);
        return headers;
    }

    private static Map<String, Object> removeOne(final UUID caseId) {
        final Map<String, Object> body = new HashMap<>();
        body.put("caseIds", List.of(caseId.toString()));
        body.put("newGroup", false);
        body.put("resultingIntent", "GRANT");
        return body;
    }

    private static Map<String, Object> newLead(final UUID caseId) {
        final Map<String, Object> leadCase = new HashMap<>();
        leadCase.put("caseId", caseId.toString());
        leadCase.put("caseUrn", "TESTURN0002");
        leadCase.put("defendantName", "Another Defendant");
        return leadCase;
    }

    private ResponseEntity<String> extract(final UUID groupId, final Map<String, Object> body, final String cjsCppUid) {
        return http.exchange(baseUrl + "/groupcases/" + groupId + "/extractcases", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(cjsCppUid)), String.class);
    }

    private ResponseEntity<String> extract(final UUID groupId, final Map<String, Object> body) {
        return extract(groupId, body, UUID.randomUUID().toString());
    }

    /** The group as stored by case management, read back through the search endpoint. */
    private String storedGroup(final StoredGroup group) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("CJSCPPUID", UUID.randomUUID().toString());
        final ResponseEntity<String> res = http.exchange(
                baseUrl + "/groupcases/search?courtCentreId=" + group.courtCentreId() + "&startDate=" + HEARING_DATE,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(group.groupId().toString());
        return res.getBody();
    }

    /** Requests WireMock received for the remove command on this case id, as WireMock's journal JSON. */
    private String removeRequestsFor(final UUID caseId) {
        final Map<String, Object> criteria = Map.of("method", "POST", "urlPath", REMOVE_PATH + caseId);
        final ResponseEntity<String> res = new RestTemplate().exchange(WIREMOCK_ADMIN_REQUESTS + "/find",
                HttpMethod.POST, new HttpEntity<>(criteria, jsonHeaders(SYSTEM_USER_ID)), String.class);
        return res.getBody();
    }

    @Test
    void removing_a_member_case_should_return_204_and_reduce_the_case_count() {
        final StoredGroup group = createGroup(5);
        final UUID memberCaseId = UUID.randomUUID();

        final ResponseEntity<String> res = extract(group.groupId(), removeOne(memberCaseId));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storedGroup(group))
                .contains("\"caseCount\":4")
                .contains("\"leadCaseId\":\"" + group.leadCaseId() + "\"");
        assertThat(removeRequestsFor(memberCaseId))
                .contains(SYSTEM_USER_ID)
                .contains(group.groupId().toString())
                .doesNotContain("newLeadCaseId");
    }

    @Test
    void removing_the_lead_case_should_store_the_new_lead_and_send_it_to_progression() {
        final StoredGroup group = createGroup(3);
        final UUID newLeadCaseId = UUID.randomUUID();
        final Map<String, Object> body = removeOne(group.leadCaseId());
        body.put("leadCase", newLead(newLeadCaseId));

        final ResponseEntity<String> res = extract(group.groupId(), body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storedGroup(group))
                .contains("\"caseCount\":2")
                .contains("\"leadCaseId\":\"" + newLeadCaseId + "\"")
                .contains("\"caseUrn\":\"TESTURN0002\"");
        assertThat(removeRequestsFor(group.leadCaseId()))
                .contains("newLeadCaseId")
                .contains(newLeadCaseId.toString());
    }

    @Test
    void removing_the_lead_case_without_a_new_lead_should_return_400_and_change_nothing() {
        final StoredGroup group = createGroup(5);

        final ResponseEntity<String> res = extract(group.groupId(), removeOne(group.leadCaseId()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("leadCase is required");
        assertThat(storedGroup(group)).contains("\"caseCount\":5");
        assertThat(removeRequestsFor(group.leadCaseId())).doesNotContain(group.groupId().toString());
    }

    @Test
    void group_with_only_one_case_should_return_400() {
        final StoredGroup group = createGroup(1);

        final ResponseEntity<String> res = extract(group.groupId(), removeOne(UUID.randomUUID()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("at least 2 cases");
        assertThat(storedGroup(group)).contains("\"caseCount\":1");
    }

    @Test
    void group_with_two_cases_should_allow_a_removal_leaving_one() {
        final StoredGroup group = createGroup(2);

        final ResponseEntity<String> res = extract(group.groupId(), removeOne(UUID.randomUUID()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storedGroup(group)).contains("\"caseCount\":1");
    }

    @Test
    void new_group_should_return_400() {
        final StoredGroup group = createGroup(5);
        final Map<String, Object> body = removeOne(UUID.randomUUID());
        body.put("newGroup", true);

        final ResponseEntity<String> res = extract(group.groupId(), body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknown_group_should_return_404() {
        final ResponseEntity<String> res = extract(UUID.randomUUID(), removeOne(UUID.randomUUID()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void progression_not_accepting_the_removal_should_return_500_and_change_nothing() {
        final StoredGroup group = createGroup(5);

        final ResponseEntity<String> res = extract(group.groupId(), removeOne(REJECTED_CASE_ID));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).contains("Progression did not accept the removal");
        assertThat(storedGroup(group)).contains("\"caseCount\":5");
    }

    @Test
    void user_without_permission_should_return_403() {
        final StoredGroup group = createGroup(5);

        final ResponseEntity<String> res = extract(group.groupId(), removeOne(UUID.randomUUID()), UNAUTHORIZED_CJSCPPUID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storedGroup(group)).contains("\"caseCount\":5");
    }
}
