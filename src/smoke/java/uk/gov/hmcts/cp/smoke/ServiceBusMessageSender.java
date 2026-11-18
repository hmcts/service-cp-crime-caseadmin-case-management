package uk.gov.hmcts.cp.smoke;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import java.util.UUID;

/**
 * Sends one JSON message to the local Service Bus emulator queue, or peeks its dead-letter queue.
 *
 * <pre>
 * ./gradlew -q sendServiceBusMessage [--args="'{\"groupId\":\"...\"}' [subject]"]
 * ./gradlew -q sendServiceBusMessage --args=--peek-dlq
 * </pre>
 *
 * <p>Prints {@code sent messageId=<id> ...}; scripts/container-smoke.sh reads the message id from that
 * line. Defaults to the docker-compose.yml emulator; override with CP_CM_SERVICEBUS_CONNECTION_STRING
 * and CP_CM_SERVICEBUS_QUEUE_NAME.
 */
public final class ServiceBusMessageSender {

    private static final String EMULATOR_CONNECTION_STRING =
            "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
    private static final int PEEK_LIMIT = 20;

    private ServiceBusMessageSender() {
    }

    public static void main(final String[] args) {
        final ServiceBusClientBuilder builder = new ServiceBusClientBuilder()
                .connectionString(env("CP_CM_SERVICEBUS_CONNECTION_STRING", EMULATOR_CONNECTION_STRING));
        final String queue = env("CP_CM_SERVICEBUS_QUEUE_NAME", "casemanagement.requests");

        if (args.length > 0 && "--peek-dlq".equals(args[0])) {
            peekDeadLetters(builder, queue);
            return;
        }

        final String body = args.length > 0 ? args[0] : "{\"groupId\":\"%s\"}".formatted(UUID.randomUUID());
        final ServiceBusMessage message = new ServiceBusMessage(body)
                .setMessageId(UUID.randomUUID().toString())
                .setContentType("application/json");
        if (args.length > 1) {
            message.setSubject(args[1]);
        }
        try (ServiceBusSenderClient sender = builder.sender().queueName(queue).buildClient()) {
            sender.sendMessage(message);
        }
        System.out.printf("sent messageId=%s to %s: %s%n", message.getMessageId(), queue, body);
    }

    private static void peekDeadLetters(final ServiceBusClientBuilder builder, final String queue) {
        try (ServiceBusReceiverClient deadLetters = builder.receiver()
                .queueName(queue)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .buildClient()) {
            for (final ServiceBusReceivedMessage message : deadLetters.peekMessages(PEEK_LIMIT)) {
                System.out.printf("%s %s %s %s%n", message.getMessageId(), message.getDeadLetterReason(),
                        message.getDeadLetterErrorDescription(), message.getBody());
            }
        }
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
