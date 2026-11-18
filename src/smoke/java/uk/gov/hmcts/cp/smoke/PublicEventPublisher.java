package uk.gov.hmcts.cp.smoke;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.time.Instant;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

/**
 * Publishes one public event to the local Artemis broker, the way the CQRS framework does: a
 * {@link TextMessage} on the {@code public.event} topic with the event name in the {@code CPPNAME}
 * property the service's selector filters on.
 *
 * <pre>./gradlew -q publishPublicEvent [--args="[groupId] [eventName]"]</pre>
 *
 * <p>Prints {@code published <eventName> groupId=<groupId> ...}; scripts/container-smoke.sh reads the
 * group id from that line. Broker and credentials default to the docker-compose.yml Artemis and can be
 * overridden with ARTEMIS_BROKER_URL, ARTEMIS_USER, ARTEMIS_PASSWORD and PUBLIC_EVENT_TOPIC.
 */
public final class PublicEventPublisher {

    private static final String DEFAULT_EVENT = "public.progression.group-prosecution-cases-created";
    private static final String SYSTEM_USER = "00000000-0000-0000-0000-000000000000";

    private PublicEventPublisher() {
    }

    public static void main(final String[] args) throws JMSException {
        final String groupId = args.length > 0 ? args[0] : UUID.randomUUID().toString();
        final String eventName = args.length > 1 ? args[1] : DEFAULT_EVENT;
        final String brokerUrl = env("ARTEMIS_BROKER_URL", "tcp://localhost:61616");
        final String topic = env("PUBLIC_EVENT_TOPIC", "public.event");

        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                brokerUrl, env("ARTEMIS_USER", "admin"), env("ARTEMIS_PASSWORD", "admin"));
             Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer producer = session.createProducer(session.createTopic(topic))) {
            final TextMessage message = session.createTextMessage(envelope(groupId, eventName));
            message.setStringProperty("CPPNAME", eventName);
            producer.send(message);
        }
        System.out.printf("published %s groupId=%s to %s address %s%n", eventName, groupId, brokerUrl, topic);
    }

    private static String envelope(final String groupId, final String eventName) {
        return """
                {"_metadata":{"id":"%s","name":"%s","createdAt":"%s","context":{"user":"%s"},\
                "stream":{"id":"%s"}},"groupId":"%s"}"""
                .formatted(UUID.randomUUID(), eventName, Instant.now(), SYSTEM_USER, groupId, groupId);
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
