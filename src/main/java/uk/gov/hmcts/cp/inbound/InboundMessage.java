package uk.gov.hmcts.cp.inbound;

import tools.jackson.databind.JsonNode;

/**
 * One delivery off the Service Bus queue, after its body has been read as JSON.
 *
 * @param messageId the broker message id (stable across redeliveries; the de-duplication key)
 * @param subject the message's Subject/Label, which senders use to name the message type; may be null
 * @param deliveryCount how many times the broker has delivered this message before
 * @param body the JSON body
 */
public record InboundMessage(String messageId, String subject, long deliveryCount, JsonNode body) {
}
