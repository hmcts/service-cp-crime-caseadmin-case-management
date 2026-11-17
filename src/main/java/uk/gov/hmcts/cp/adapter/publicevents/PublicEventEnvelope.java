package uk.gov.hmcts.cp.adapter.publicevents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A CQRS framework public event as it arrives on {@code public.event}: a JsonEnvelope whose
 * {@code _metadata} carries the event name (plus id, createdAt, user/session context...) and whose
 * remaining top-level fields are the payload.
 *
 * @param metadataName {@code _metadata.name}, or null when absent
 * @param payload the envelope with {@code _metadata} removed
 */
public record PublicEventEnvelope(String metadataName, JsonNode payload) {

    private static final String METADATA = "_metadata";
    private static final String NAME = "name";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Reads a message body as a JsonEnvelope.
     *
     * @throws tools.jackson.core.JacksonException if the body is not JSON
     * @throws IllegalArgumentException if the body is JSON but not an object
     */
    public static PublicEventEnvelope parse(final String body) {
        final JsonNode envelope = MAPPER.readTree(body);
        if (envelope == null || !envelope.isObject()) {
            throw new IllegalArgumentException(
                    "a public event body is a JsonEnvelope object, and this one is not");
        }
        final JsonNode name = envelope.path(METADATA).path(NAME);
        final String metadataName = name.isString() ? name.stringValue() : null;
        final ObjectNode payload = (ObjectNode) envelope;
        payload.remove(METADATA);
        return new PublicEventEnvelope(metadataName, payload);
    }
}
