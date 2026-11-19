package uk.gov.hmcts.cp.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
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
 * Black-box test of the Azure Monitor metrics against the dockerised app. The app's connection
 * string points Application Insights ingestion at WireMock (docker-compose.integration.yml, export
 * every 2s), so the telemetry the app would send to Azure is read back from WireMock's request
 * journal. Proves, through real HTTP requests:
 * <ul>
 *   <li>an exception opted in with {@code @PublishToAzureMonitor} (Progression rejecting a removal)
 *       reaches Azure Monitor with its dimensions;</li>
 *   <li>an exception that is not opted in (unknown group, 404) never does.</li>
 * </ul>
 */
public class AzureMonitorHttpLiveTest extends AbstractTest {

    private static final String TRACK_PATH = "/v2.1/track";
    private static final String EXCEPTIONS_METRIC = "\"case_management_exceptions\"";
    private static final UUID REJECTED_CASE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Duration EXPORT_WAIT = Duration.ofSeconds(45);
    private static final long POLL_MILLIS = 500;

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casemanagement-service");
    private final RestTemplate http = new RestTemplate();

    AzureMonitorHttpLiveTest() {
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(final ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Test
    void opted_in_exception_reaches_azure_monitor_and_unselected_one_does_not() throws InterruptedException {
        final Set<String> before = exportIds();

        // Not opted in: GroupCaseNotFoundException -> 404
        final ResponseEntity<String> notFound = extract(UUID.randomUUID(), removeOne(UUID.randomUUID()));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Opted in: ProgressionCommandRejectedException -> 500 (WireMock rejects this case id)
        final ResponseEntity<String> rejected = extract(createGroup(), removeOne(REJECTED_CASE_ID));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // Only exports since `before` count, so telemetry from earlier test classes cannot satisfy
        // this test; and waiting for one that STARTED after both requests means both have been
        // exported by then, whatever the exporter's temporality.
        final List<String> items = telemetrySince(before, awaitExportAfter(exportIds()));

        assertThat(items).anySatisfy(item -> assertThat(item)
                .contains(EXCEPTIONS_METRIC)
                .contains("\"exception_type\":\"ProgressionCommandRejectedException\"")
                .contains("\"source\":\"rest\"")
                .contains("\"http_status\":\"500\"")
                .contains("\"ai.cloud.role\":\"service-cp-crime-caseadmin-case-management\""));
        assertThat(items).noneSatisfy(item -> assertThat(item).contains("GroupCaseNotFoundException"));
    }

    @Test
    void only_metrics_are_sent_to_azure_monitor() throws InterruptedException {
        final Set<String> before = exportIds();
        extract(createGroup(), removeOne(REJECTED_CASE_ID));

        final List<String> items = telemetrySince(before, awaitExportAfter(exportIds()));

        // Requests, dependencies, traces and logs stay out: the exporter is metrics-only
        assertThat(items).isNotEmpty()
                .allSatisfy(item -> assertThat(item).contains("\"baseType\":\"MetricData\""));
    }

    // ---------- app calls ----------

    private UUID createGroup() {
        final UUID groupId = UUID.randomUUID();
        final Map<String, Object> leadCase = new HashMap<>();
        leadCase.put("leadCaseId", UUID.randomUUID().toString());
        leadCase.put("caseUrn", "TESTURN0001");
        leadCase.put("defendantName", "Example Defendant");

        final Map<String, Object> body = new HashMap<>();
        body.put("groupId", groupId.toString());
        body.put("leadCase", leadCase);
        body.put("dateOfHearing", "2026-12-01");
        body.put("caseCount", 5);
        body.put("offenceType", "Council Tax");
        body.put("courtHearingLocation", "B01LY01");
        body.put("courtCentreId", UUID.randomUUID().toString());
        body.put("prosecutingAuthority", "EXAMPLE01");

        final ResponseEntity<String> res = http.exchange(baseUrl + "/groupcases", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return groupId;
    }

    private ResponseEntity<String> extract(final UUID groupId, final Map<String, Object> body) {
        return http.exchange(baseUrl + "/groupcases/" + groupId + "/extractcases", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private static Map<String, Object> removeOne(final UUID caseId) {
        final Map<String, Object> body = new HashMap<>();
        body.put("caseIds", List.of(caseId.toString()));
        body.put("newGroup", false);
        body.put("resultingIntent", "GRANT");
        return body;
    }

    private static HttpHeaders jsonHeaders() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("CJSCPPUID", UUID.randomUUID().toString());
        return headers;
    }

    // ---------- WireMock journal: what the app sent to "Azure" ----------

    /** The journal ids of every export (POST /v2.1/track) received so far. */
    private static Set<String> exportIds() {
        return exports().stream().map(request -> String.valueOf(request.get("id"))).collect(Collectors.toSet());
    }

    /** Waits for an export that is not in {@code seen}, i.e. one that started after it was taken. */
    private static Set<String> awaitExportAfter(final Set<String> seen) throws InterruptedException {
        final Instant deadline = Instant.now().plus(EXPORT_WAIT);
        Set<String> now = exportIds();
        while (seen.containsAll(now) && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_MILLIS);
            now = exportIds();
        }
        assertThat(now).as("an export to the ingestion endpoint within %s", EXPORT_WAIT)
                .doesNotContain("null")
                .isNotEqualTo(seen);
        return now;
    }

    /** Telemetry items (one JSON document per line, whitespace removed) in exports after {@code before}. */
    private static List<String> telemetrySince(final Set<String> before, final Set<String> upTo) {
        final List<String> items = new ArrayList<>();
        for (final Map<?, ?> request : exports()) {
            final String id = String.valueOf(request.get("id"));
            if (!before.contains(id) && upTo.contains(id) && request.get("bodyAsBase64") instanceof String base64) {
                decode(base64).lines().filter(line -> !line.isBlank())
                        .map(line -> line.replaceAll("\\s", ""))   // values here hold no spaces
                        .forEach(items::add);
            }
        }
        return items;
    }

    /**
     * Every export (POST /v2.1/track) in WireMock's journal, each as its logged request plus the
     * journal {@code id}. The full journal is read because only it carries ids; /requests/find does not.
     */
    private static List<Map<?, ?>> exports() {
        final ResponseEntity<Map<String, Object>> res = new RestTemplate().exchange(WIREMOCK_ADMIN_REQUESTS,
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() { });
        final List<Map<?, ?>> exports = new ArrayList<>();
        if (res.getBody() != null && res.getBody().get("requests") instanceof List<?> events) {
            for (final Object event : events) {
                if (event instanceof Map<?, ?> served && served.get("request") instanceof Map<?, ?> request
                        && "POST".equals(request.get("method"))
                        && String.valueOf(request.get("url")).startsWith(TRACK_PATH)) {
                    final Map<Object, Object> withId = new java.util.HashMap<>(request);
                    withId.put("id", served.get("id"));
                    exports.add(withId);
                }
            }
        }
        return exports;
    }

    /** The posted body, gunzipped when the exporter compressed it. */
    private static String decode(final String base64) {
        final byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
