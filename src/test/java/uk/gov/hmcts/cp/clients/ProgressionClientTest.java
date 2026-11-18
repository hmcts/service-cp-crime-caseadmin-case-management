package uk.gov.hmcts.cp.clients;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.config.AppConfig;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;

/**
 * Exercises the real RestClient against WireMock to prove Progression's vendor media type
 * (application/vnd.progression.query.group-cases+json) actually deserialises, since Jackson's
 * supported-media-types list for a RestClient is otherwise easy to get wrong silently.
 */
class ProgressionClientTest {

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID NEW_LEAD_CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-0000000000aa";
    private static final String REMOVE_PATH =
            "/progression-command-api/command/api/rest/progression/prosecutioncases/" + CASE_ID;
    private static final String REMOVE_MEDIA_TYPE = "application/vnd.progression.remove-case-from-group-cases+json";

    private WireMockServer wireMockServer;
    private ProgressionClient progressionClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        progressionClient = new ProgressionClient(RestClient.builder()
                .requestFactory(AppConfig.http11RequestFactory())
                .baseUrl(wireMockServer.baseUrl())
                .build());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void getGroupCaseMembers_should_deserialise_the_vendor_media_type_response() {
        wireMockServer.stubFor(get(urlEqualTo("/progression-query-api/query/api/rest/progression/prosecutioncases/group/" + GROUP_ID))
                .withHeader("CJSCPPUID", equalTo("cjscppuid"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/vnd.progression.query.group-cases+json")
                        .withBody("""
                                {
                                  "groupId": "%s",
                                  "leadCase": {
                                    "caseId": "00000000-0000-0000-0000-000000000002",
                                    "caseUrn": "TESTURN0001",
                                    "defendantName": "Example Defendant"
                                  },
                                  "cases": [
                                    {
                                      "caseId": "00000000-0000-0000-0000-000000000002",
                                      "caseUrn": "TESTURN0001",
                                      "defendantName": "Example Defendant",
                                      "address": { "address1": "1 Example Street", "postcode": "AB1 2CD" }
                                    }
                                  ]
                                }
                                """.formatted(GROUP_ID))));

        final ProgressionGroupCaseResponse response = progressionClient.getGroupCaseMembers("cjscppuid", GROUP_ID);

        assertThat(response.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(response.getLeadCase().getCaseUrn()).isEqualTo("TESTURN0001");
        assertThat(response.getCases()).hasSize(1);
        assertThat(response.getCases().get(0).getAddress().getPostcode()).isEqualTo("AB1 2CD");
    }

    @Test
    void removeCaseFromGroup_should_post_the_command_as_the_system_user_without_a_new_lead() {
        wireMockServer.stubFor(post(urlEqualTo(REMOVE_PATH)).willReturn(aResponse().withStatus(202)));

        progressionClient.removeCaseFromGroup(SYSTEM_USER_ID, CASE_ID, GROUP_ID, null);

        wireMockServer.verify(postRequestedFor(urlEqualTo(REMOVE_PATH))
                .withHeader("CJSCPPUID", equalTo(SYSTEM_USER_ID))
                .withHeader("Content-Type", equalTo(REMOVE_MEDIA_TYPE))
                .withRequestBody(equalToJson("""
                        {"groupId": "%s"}
                        """.formatted(GROUP_ID))));
    }

    @Test
    void removeCaseFromGroup_should_send_the_new_lead_when_given() {
        wireMockServer.stubFor(post(urlEqualTo(REMOVE_PATH)).willReturn(aResponse().withStatus(202)));

        progressionClient.removeCaseFromGroup(SYSTEM_USER_ID, CASE_ID, GROUP_ID, NEW_LEAD_CASE_ID);

        wireMockServer.verify(postRequestedFor(urlEqualTo(REMOVE_PATH))
                .withRequestBody(equalToJson("""
                        {"groupId": "%s", "newLeadCaseId": "%s"}
                        """.formatted(GROUP_ID, NEW_LEAD_CASE_ID))));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 400, 403, 404, 500, 503})
    void removeCaseFromGroup_should_reject_any_status_other_than_202(final int status) {
        wireMockServer.stubFor(post(urlEqualTo(REMOVE_PATH)).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> progressionClient.removeCaseFromGroup(SYSTEM_USER_ID, CASE_ID, GROUP_ID, null))
                .isInstanceOfSatisfying(ProgressionCommandRejectedException.class,
                        exception -> assertThat(exception.getMessage()).contains(String.valueOf(status)));
    }

    @Test
    void removeCaseFromGroup_should_reject_when_progression_cannot_be_reached() {
        wireMockServer.stop();

        assertThatThrownBy(() -> progressionClient.removeCaseFromGroup(SYSTEM_USER_ID, CASE_ID, GROUP_ID, null))
                .isInstanceOf(ProgressionCommandRejectedException.class);
    }
}
