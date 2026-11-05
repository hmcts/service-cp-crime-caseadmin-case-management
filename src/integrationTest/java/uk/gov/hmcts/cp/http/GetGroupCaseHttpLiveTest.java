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
 * Black-box test of GET /groupcases/{groupId} against the dockerised app, its Postgres and the
 * WireMock stand-in for Progression. Group ids are random (the live stack's Postgres persists
 * across local re-runs, so a fixed id would conflict with a prior run's row); the WireMock stub
 * mappings key off the forwarded CJSCPPUID header instead, which this test fully controls.
 */
public class GetGroupCaseHttpLiveTest extends AbstractTest {

    private static final String NO_MISMATCH_CJSCPPUID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String MISMATCH_CJSCPPUID = "aaaaaaaa-0000-0000-0000-000000000002";

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casemanagement-service");
    private final RestTemplate http = new RestTemplate();

    GetGroupCaseHttpLiveTest() {
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(final ClientHttpResponse response) {
                return false;
            }
        });
    }

    private static Map<String, Object> groupCase(final UUID groupId, final int caseCount) {
        final Map<String, Object> leadCase = new HashMap<>();
        leadCase.put("leadCaseId", UUID.randomUUID().toString());
        leadCase.put("caseUrn", "TESTURN0001");
        leadCase.put("defendantName", "Example Defendant");

        final Map<String, Object> body = new HashMap<>();
        body.put("groupId", groupId.toString());
        body.put("leadCase", leadCase);
        body.put("dateOfHearing", "2026-12-01");
        body.put("caseCount", caseCount);
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

    private ResponseEntity<String> get(final UUID groupId, final String cjsCppUid) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("CJSCPPUID", cjsCppUid);
        return http.exchange(baseUrl + "/groupcases/" + groupId, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void get_group_with_matching_case_count_should_return_200_without_warning() {
        final UUID groupId = UUID.randomUUID();
        assertThat(create(groupCase(groupId, 1)).getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> res = get(groupId, NO_MISMATCH_CJSCPPUID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"caseUrn\":\"TESTURN0001\"").contains("\"warning\":null");
    }

    @Test
    void get_group_with_mismatched_case_count_should_return_200_with_warning() {
        final UUID groupId = UUID.randomUUID();
        assertThat(create(groupCase(groupId, 4)).getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> res = get(groupId, MISMATCH_CJSCPPUID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("\"warning\":\"CASE_COUNT_MISMATCH\"")
                .contains("\"storedCaseCount\":4")
                .contains("\"actualCaseCount\":2");
    }

    @Test
    void get_group_unknown_to_progression_should_return_404() {
        final UUID groupId = UUID.randomUUID();
        assertThat(create(groupCase(groupId, 1)).getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> res = get(groupId, UUID.randomUUID().toString());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_unknown_group_should_return_404() {
        final ResponseEntity<String> res = get(UUID.randomUUID(), UUID.randomUUID().toString());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"error\":\"Not Found\"");
    }
}
