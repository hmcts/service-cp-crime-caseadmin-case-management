package uk.gov.hmcts.cp.monitoring;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;

/**
 * End to end through the real Azure Monitor exporter: the Spring wiring publishes, and the exporter
 * sends Application Insights' ingestion protocol ({@code POST /v2.1/track}) to a WireMock standing
 * in for Azure. Proves the bytes Azure would receive: metric names, values, dimensions and role
 * name; and that nothing else (traces, logs, unselected exceptions) is sent.
 */
class AzureMonitorExportIntegrationTest {

    private static final String TRACK_PATH = "/v2.1/track";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WireMockServer azure;

    @BeforeEach
    void startFakeAzure() {
        azure = new WireMockServer(options().dynamicPort());
        azure.start();
        azure.stubFor(post(urlPathEqualTo(TRACK_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"itemsReceived\":1,\"itemsAccepted\":1,\"errors\":[]}")));
    }

    @AfterEach
    void stopFakeAzure() {
        azure.stop();
    }

    private ApplicationContextRunner enabledContext() {
        final String endpoint = "http://localhost:" + azure.port() + "/";
        return new ApplicationContextRunner()
                .withUserConfiguration(AzureMonitorConfig.class, PublishMetricsService.class)
                .withPropertyValues(
                        "spring.application.name=case-management-under-test",
                        "casemanagement.azure-monitor.enabled=true",
                        "casemanagement.azure-monitor.export-interval=1h",   // only the flush on close exports
                        "casemanagement.azure-monitor.connection-string=InstrumentationKey="
                                + "00000000-0000-0000-0000-000000000000;IngestionEndpoint=" + endpoint
                                + ";LiveEndpoint=" + endpoint);
    }

    @Test
    void selected_exception_and_event_reach_application_insights_on_shutdown() {
        enabledContext().run(context -> {
            final PublishMetricsService metrics = context.getBean(PublishMetricsService.class);
            metrics.publishExceptionIfEnabled("rest",
                    new ProgressionCommandRejectedException(HttpStatus.INTERNAL_SERVER_ERROR),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            metrics.publishExceptionIfEnabled("rest",
                    new ProgressionCommandRejectedException(HttpStatus.INTERNAL_SERVER_ERROR),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            metrics.publishMessageSettlement("deadletter", "VALIDATION");
        });   // closing the context flushes the exporter

        final List<JsonNode> items = telemetryItems();
        final JsonNode exceptions = metricItem(items, PublishMetricsService.EXCEPTIONS_METRIC);
        assertThat(exceptions.at("/data/baseData/metrics/0/value").asDouble()).isEqualTo(2.0);
        assertThat(properties(exceptions)).containsEntry("source", "rest")
                .containsEntry("exception_type", "ProgressionCommandRejectedException")
                .containsEntry("http_status", "500");
        assertThat(exceptions.at("/tags/ai.cloud.role").asString()).isEqualTo("case-management-under-test");

        final JsonNode settlements = metricItem(items, PublishMetricsService.MESSAGE_SETTLEMENTS_METRIC);
        assertThat(properties(settlements)).containsEntry("outcome", "deadletter").containsEntry("reason", "VALIDATION");
    }

    @Test
    void unselected_exceptions_and_other_signals_are_never_sent() {
        enabledContext().run(context -> {
            final PublishMetricsService metrics = context.getBean(PublishMetricsService.class);
            metrics.publishExceptionIfEnabled("rest", new GroupCaseNotFoundException(java.util.UUID.randomUUID()),
                    HttpStatus.NOT_FOUND);
            metrics.publishEvent("case_management_marker", "Marker so one export happens", Map.of());
        });

        final List<JsonNode> items = telemetryItems();
        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(item ->
                assertThat(item.at("/data/baseType").asString()).isEqualTo("MetricData"));
        assertThat(items.toString()).doesNotContain("GroupCaseNotFoundException");
        assertThat(items.stream().map(item -> item.at("/data/baseData/metrics/0/name").asString()))
                .doesNotContain(PublishMetricsService.EXCEPTIONS_METRIC);
    }

    @Test
    void nothing_is_sent_when_nothing_is_published() {
        enabledContext().run(context -> assertThat(context).hasNotFailed());

        assertThat(telemetryItems()).isEmpty();
    }

    @Test
    void nothing_is_sent_when_disabled() {
        enabledContext().withPropertyValues("casemanagement.azure-monitor.enabled=false").run(context ->
                context.getBean(PublishMetricsService.class).publishExceptionIfEnabled("rest",
                        new ProgressionCommandRejectedException(HttpStatus.INTERNAL_SERVER_ERROR),
                        HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(azure.findAll(postRequestedFor(urlPathEqualTo(TRACK_PATH)))).isEmpty();
    }

    // ---------- helpers ----------

    /** Every telemetry item posted to the ingestion endpoint (each request is newline-delimited JSON). */
    private List<JsonNode> telemetryItems() {
        return azure.findAll(postRequestedFor(urlPathEqualTo(TRACK_PATH))).stream()
                .map(AzureMonitorExportIntegrationTest::body)
                .flatMap(String::lines)
                .filter(line -> !line.isBlank())
                .map(JSON::readTree)
                .toList();
    }

    private static JsonNode metricItem(final List<JsonNode> items, final String metricName) {
        return items.stream()
                .filter(item -> metricName.equals(item.at("/data/baseData/metrics/0/name").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(metricName + " was not sent; items: " + items));
    }

    private static Map<String, String> properties(final JsonNode item) {
        final Map<String, String> properties = new java.util.LinkedHashMap<>();
        item.at("/data/baseData/properties").properties()
                .forEach(entry -> properties.put(entry.getKey(), entry.getValue().asString()));
        return properties;
    }

    /** The request body, gunzipped if the exporter compressed it and WireMock did not. */
    private static String body(final LoggedRequest request) {
        final byte[] bytes = request.getBody();
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void export_contacts_only_the_ingestion_endpoint() {
        enabledContext().run(context -> context.getBean(PublishMetricsService.class)
                .publishEvent("case_management_marker", "Marker so one export happens", Map.of()));

        // Live Metrics being off is proven in AzureMonitorExporterTest (its first ping comes ~10s in)
        assertThat(azure.getAllServeEvents())
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getRequest().getUrl()).startsWith(TRACK_PATH));
    }
}
