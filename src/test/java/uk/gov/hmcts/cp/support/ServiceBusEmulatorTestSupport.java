package uk.gov.hmcts.cp.support;

import static org.awaitility.Awaitility.await;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One Azure Service Bus emulator for the whole test JVM, configured from the same
 * {@code docker/servicebus-emulator/config.json} docker-compose uses (so the queue under test is the
 * queue a developer runs against), reached through a Toxiproxy so a suite can cut the broker
 * connection and restore it. Ported from yot-results-distribution's support class of the same name.
 *
 * <p>The containers start on first use and are never stopped by a test: Testcontainers' reaper
 * removes them when the JVM exits, and starting the emulator (and its SQL Server) costs tens of
 * seconds, so every suite shares the one instance.
 */
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.AvoidSynchronizedAtMethodLevel"})
public final class ServiceBusEmulatorTestSupport {

    /** Must match docker/servicebus-emulator/config.json and casemanagement.servicebus.queue-name. */
    public static final String QUEUE_NAME = "casemanagement.requests";
    /** Must match the queue's MaxDeliveryCount in docker/servicebus-emulator/config.json. */
    public static final int MAX_DELIVERY_COUNT = 5;

    private static final int PEEK_PAGE_SIZE = 32;
    private static final String EMULATOR_IMAGE =
            "mcr.microsoft.com/azure-messaging/servicebus-emulator:1.1.2";
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    private static final Path CONFIG_PATH =
            Paths.get("docker", "servicebus-emulator", "config.json").toAbsolutePath();
    private static final int AMQP_PORT = 5672;
    private static final String EMULATOR_ALIAS = "servicebus";
    private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";
    private static final int TOXIPROXY_CONTROL_PORT = 8474;
    private static final int TOXIPROXY_LISTEN_PORT = 8666;
    private static final String PROXY_NAME = "servicebus";
    private static final Duration BROKER_RETURNS_WITHIN = Duration.ofSeconds(120);

    private static final Network NETWORK = Network.newNetwork();
    private static final MSSQLServerContainer MSSQL = new MSSQLServerContainer(MSSQL_IMAGE)
            .acceptLicense()
            .withNetwork(NETWORK);
    private static final ServiceBusEmulatorContainer EMULATOR =
            new ServiceBusEmulatorContainer(EMULATOR_IMAGE)
                    .acceptLicense()
                    .withNetwork(NETWORK)
                    .withConfig(MountableFile.forHostPath(CONFIG_PATH))
                    .withMsSqlServerContainer(MSSQL)
                    .withNetworkAliases(EMULATOR_ALIAS);
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(TOXIPROXY_IMAGE)
            .withNetwork(NETWORK)
            .withExposedPorts(TOXIPROXY_CONTROL_PORT, TOXIPROXY_LISTEN_PORT);
    private static final Map<SubQueue, ServiceBusReceiverClient> PEEKERS = new EnumMap<>(SubQueue.class);

    private static boolean started;
    private static Proxy brokerProxy;

    private ServiceBusEmulatorTestSupport() {
    }

    /** Starts the emulator, its SQL Server and the proxy once per JVM. */
    public static synchronized ServiceBusEmulatorContainer container() {
        if (!started) {
            MSSQL.start();
            EMULATOR.start();
            TOXIPROXY.start();
            brokerProxy = createProxy();
            started = true;
        }
        return EMULATOR;
    }

    private static Proxy createProxy() {
        try {
            return new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy(PROXY_NAME, "0.0.0.0:" + TOXIPROXY_LISTEN_PORT,
                            EMULATOR_ALIAS + ':' + AMQP_PORT);
        } catch (IOException unreachable) {
            throw new UncheckedIOException("could not put a proxy in front of the broker", unreachable);
        }
    }

    private static synchronized Proxy proxy() {
        container();
        return brokerProxy;
    }

    /** The emulator's development connection string, pointed at the proxy rather than the broker. */
    public static String connectionString() {
        container();
        return "Endpoint=sb://" + TOXIPROXY.getHost() + ':'
                + TOXIPROXY.getMappedPort(TOXIPROXY_LISTEN_PORT)
                + ";SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;"
                + "UseDevelopmentEmulator=true;";
    }

    /** Cuts every connection to the broker, as a network outage would. */
    public static void disconnect() {
        try {
            proxy().disable();
        } catch (IOException unreachable) {
            throw new UncheckedIOException("could not cut the broker connection", unreachable);
        }
        discardPeekers();
    }

    /** Restores the broker connection and waits until the broker answers through it. */
    public static void reconnect() {
        try {
            proxy().enable();
        } catch (IOException unreachable) {
            throw new UncheckedIOException("could not restore the broker connection", unreachable);
        }
        discardPeekers();
        await().atMost(BROKER_RETURNS_WITHIN)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(ServiceBusEmulatorTestSupport::assertBrokerAnswers);
    }

    /** For an @AfterEach: reconnects if a test left the broker cut off. */
    public static void restore() {
        if (!proxy().isEnabled()) {
            reconnect();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void assertBrokerAnswers() {
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(connectionString())
                .receiver()
                .queueName(QUEUE_NAME)
                .buildClient()) {
            receiver.peekMessage();
        } catch (RuntimeException unreachable) {
            throw new AssertionError("the broker did not answer through the proxy", unreachable);
        }
    }

    /** Sends a JSON body under a fresh message id and returns that id. */
    public static String send(final String body) {
        return send(UUID.randomUUID().toString(), body);
    }

    /** Sends a body under the given message id (duplicate detection keys on it). */
    public static String send(final String messageId, final String body) {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString())
                .sender()
                .queueName(QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(new ServiceBusMessage(BinaryData.fromString(body))
                    .setMessageId(messageId)
                    .setSubject("casemanagement.test")
                    .setContentType("application/json"));
        }
        return messageId;
    }

    /** Looks for a message by id on the queue or one of its sub-queues without receiving it. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static Optional<ServiceBusReceivedMessage> peekFor(final String messageId, final SubQueue subQueue) {
        try {
            return pageFor(peeker(subQueue), messageId);
        } catch (RuntimeException unanswered) {
            discardPeekers();
            throw unanswered;
        }
    }

    private static synchronized ServiceBusReceiverClient peeker(final SubQueue subQueue) {
        return PEEKERS.computeIfAbsent(subQueue, sub -> new ServiceBusClientBuilder()
                .connectionString(connectionString())
                .receiver()
                .queueName(QUEUE_NAME)
                .subQueue(sub)
                .buildClient());
    }

    private static synchronized void discardPeekers() {
        PEEKERS.values().forEach(ServiceBusReceiverClient::close);
        PEEKERS.clear();
    }

    private static Optional<ServiceBusReceivedMessage> pageFor(
            final ServiceBusReceiverClient receiver, final String messageId) {
        Optional<ServiceBusReceivedMessage> found = Optional.empty();
        long fromSequenceNumber = 0L;
        boolean queueHasMore = true;
        while (found.isEmpty() && queueHasMore) {
            final List<ServiceBusReceivedMessage> page =
                    receiver.peekMessages(PEEK_PAGE_SIZE, fromSequenceNumber).stream().toList();
            queueHasMore = !page.isEmpty();
            if (queueHasMore) {
                found = page.stream().filter(message -> messageId.equals(message.getMessageId())).findFirst();
                fromSequenceNumber = page.get(page.size() - 1).getSequenceNumber() + 1;
            }
        }
        return found;
    }
}
