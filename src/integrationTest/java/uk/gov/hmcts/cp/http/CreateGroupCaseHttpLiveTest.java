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
 * Black-box test of POST /groupcases against the dockerised app and its Postgres.
 * The request data is made up.
 */
public class CreateGroupCaseHttpLiveTest extends AbstractTest {

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casemanagement-service");
    private final RestTemplate http = new RestTemplate();

    CreateGroupCaseHttpLiveTest() {
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
        body.put("caseCount", 250);
        body.put("offenceType", "Council Tax");
        body.put("courtHearingLocation", "B01LY01");
        body.put("courtCentreId", UUID.randomUUID().toString());
        body.put("courtroomId", UUID.randomUUID().toString());
        body.put("prosecutingAuthority", "EXAMPLE01");
        body.put("prosecutingAuthorityName", "Example Council");
        return body;
    }

    private ResponseEntity<String> post(final Object body) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("CJSCPPUID", UUID.randomUUID().toString());
        return http.exchange(baseUrl + "/groupcases", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void create_new_group_should_return_200() {
        final ResponseEntity<String> res = post(groupCase(UUID.randomUUID()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void create_same_group_twice_should_return_409_the_second_time() {
        final Map<String, Object> body = groupCase(UUID.randomUUID());

        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> second = post(body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"error\":\"Conflict\"");
    }

    @Test
    void create_split_group_with_existing_parent_should_return_200() {
        final UUID parentGroupId = UUID.randomUUID();
        assertThat(post(groupCase(parentGroupId)).getStatusCode()).isEqualTo(HttpStatus.OK);

        final Map<String, Object> child = groupCase(UUID.randomUUID());
        child.put("parentGroupId", parentGroupId.toString());
        child.put("caseCount", 12);
        child.put("resultingIntent", "WITHDRAW");

        assertThat(post(child).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void create_with_unknown_parent_should_return_404() {
        final Map<String, Object> body = groupCase(UUID.randomUUID());
        body.put("parentGroupId", UUID.randomUUID().toString());

        final ResponseEntity<String> res = post(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("\"error\":\"Not Found\"");
    }

    @Test
    void create_with_invalid_payload_should_return_400() {
        final Map<String, Object> body = groupCase(UUID.randomUUID());
        body.remove("courtCentreId");
        body.put("courtHearingLocation", "B01");

        final ResponseEntity<String> res = post(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("courtCentreId").contains("courtHearingLocation");
    }
}
