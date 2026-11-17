package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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
 * Black-box test of GET /groupcases/resultintent against the dockerised app and its Postgres.
 * The lookup is by stored group_id only, so no WireMock stub is needed for the happy path; the
 * unauthorized case reuses the shared usersgroups permissions stub keyed off CJSCPPUID.
 */
public class GetGroupCaseResultingIntentsHttpLiveTest extends AbstractTest {

    private static final String UNAUTHORIZED_CJSCPPUID = "aaaaaaaa-0000-0000-0000-000000000003";

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casemanagement-service");
    private final RestTemplate http = new RestTemplate();

    GetGroupCaseResultingIntentsHttpLiveTest() {
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(final ClientHttpResponse response) {
                return false;
            }
        });
    }

    private static Map<String, Object> groupCase(final UUID groupId) {
        final Map<String, Object> leadCase = new HashMap<>();
        leadCase.put("leadCaseId", UUID.randomUUID().toString());
        leadCase.put("caseUrn", "TESTURN0001");
        leadCase.put("defendantName", "Example Defendant");

        final Map<String, Object> body = new HashMap<>();
        body.put("groupId", groupId.toString());
        body.put("leadCase", leadCase);
        body.put("dateOfHearing", "2026-12-01");
        body.put("caseCount", 1);
        body.put("offenceType", "Council Tax");
        body.put("courtHearingLocation", "B01LY01");
        body.put("courtCentreId", UUID.randomUUID().toString());
        body.put("prosecutingAuthority", "EXAMPLE01");
        return body;
    }

    private ResponseEntity<String> create(final Map<String, Object> body) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("CJSCPPUID", UUID.randomUUID().toString());
        return http.exchange(baseUrl + "/groupcases", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> resultingIntents(final String cjsCppUid, final String groupIds) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("CJSCPPUID", cjsCppUid);
        return http.exchange(baseUrl + "/groupcases/resultintent?groupIds=" + groupIds,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void known_group_should_return_its_resulting_intent() {
        final UUID groupId = UUID.randomUUID();
        final Map<String, Object> body = groupCase(groupId);
        body.put("resultingIntent", "WITHDRAW");
        assertThat(create(body).getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> res = resultingIntents(UUID.randomUUID().toString(), groupId.toString());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("\"groupId\":\"" + groupId + "\"")
                .contains("\"resultingIntent\":\"WITHDRAW\"");
    }

    @Test
    void multiple_group_ids_should_return_an_entry_for_each_known_group() {
        final UUID firstGroupId = UUID.randomUUID();
        final UUID secondGroupId = UUID.randomUUID();
        assertThat(create(groupCase(firstGroupId)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(create(groupCase(secondGroupId)).getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> res = resultingIntents(UUID.randomUUID().toString(),
                firstGroupId + "," + secondGroupId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("\"groupId\":\"" + firstGroupId + "\"")
                .contains("\"groupId\":\"" + secondGroupId + "\"");
    }

    @Test
    void unknown_group_should_be_omitted_with_200() {
        final ResponseEntity<String> res = resultingIntents(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("{\"groupCases\":[]}");
    }

    @Test
    void missing_groupIds_should_return_400() {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("CJSCPPUID", UUID.randomUUID().toString());
        final ResponseEntity<String> res = http.exchange(baseUrl + "/groupcases/resultintent",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("groupIds");
    }

    @Test
    void missing_cjscppuid_header_should_return_401() {
        final ResponseEntity<String> res = http.exchange(
                baseUrl + "/groupcases/resultintent?groupIds=" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void user_with_no_view_permission_should_return_403() {
        final ResponseEntity<String> res = resultingIntents(UNAUTHORIZED_CJSCPPUID, UUID.randomUUID().toString());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
