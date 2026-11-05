package uk.gov.hmcts.cp.clients;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Exercises the real RestClient against WireMock to prove Progression's vendor media type
 * (application/vnd.progression.query.group-cases+json) actually deserialises, since Jackson's
 * supported-media-types list for a RestClient is otherwise easy to get wrong silently.
 */
class ProgressionClientTest {

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WireMockServer wireMockServer;
    private ProgressionClient progressionClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        progressionClient = new ProgressionClient(RestClient.builder().baseUrl(wireMockServer.baseUrl()).build());
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
}
