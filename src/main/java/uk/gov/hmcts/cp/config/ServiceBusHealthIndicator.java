package uk.gov.hmcts.cp.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether the Service Bus consumer can reach its queue, from what the processor has seen,
 * using yot-results-distribution's rule.
 *
 * <p>The SDK says nothing about a lost connection while the consumer is idle, and nothing about a
 * broker it could never reach at startup: it retries silently. So the evidence is what DID happen:
 * <ul>
 *   <li><strong>No fault recorded:</strong> UP once the broker has answered at all (a delivery or an
 *       accepted settlement), or while still inside one staleness window of intake starting. A
 *       consumer that has never once been answered after that says DOWN; any contact clears it.</li>
 *   <li><strong>A fault recorded</strong> (processor error, refused settlement): UP again only when
 *       the broker has answered SINCE the fault, or the fault is older than the window and the broker
 *       has answered before. An idle queue is innocent only when the connection is known to have
 *       worked.</li>
 * </ul>
 */
@Slf4j
public class ServiceBusHealthIndicator implements HealthIndicator {

    private static final String NONE = "none";

    private final Duration staleness;
    private final Clock clock;
    private final AtomicReference<Fault> lastFault = new AtomicReference<>();
    private final AtomicReference<Instant> lastTraffic = new AtomicReference<>();
    private final AtomicReference<Instant> intakeStartedAt = new AtomicReference<>();

    public ServiceBusHealthIndicator(final Duration staleness, final Clock clock) {
        this.staleness = staleness;
        this.clock = clock;
    }

    /** Records an error reported by the processor (connection, auth, missing entity...). */
    public void recordError(final String source, final Throwable failure) {
        lastFault.set(new Fault(source + ':' + failure.getClass().getSimpleName(), clock.instant()));
    }

    /** Records that intake has started; the startup grace window is counted from here. */
    public void recordIntakeStarted() {
        intakeStartedAt.compareAndSet(null, clock.instant());
    }

    /** Records that the broker delivered a message or accepted a settlement. */
    public void recordTraffic() {
        lastTraffic.set(clock.instant());
    }

    @Override
    public Health health() {
        final Fault fault = lastFault.get();
        final Instant traffic = lastTraffic.get();
        return (reachable(fault, traffic) ? Health.up() : Health.down())
                .withDetail("condition", fault == null ? NONE : fault.condition())
                .withDetail("lastErrorAt", fault == null ? NONE : fault.at().toString())
                .withDetail("lastTrafficAt", traffic == null ? NONE : traffic.toString())
                .withDetail("stalenessWindow", staleness.toString())
                .withDetail("intakeStartedAt", intakeStartedAt.get() == null ? NONE : intakeStartedAt.get().toString())
                .build();
    }

    private boolean reachable(final Fault fault, final Instant traffic) {
        final boolean answered;
        if (fault == null) {
            answered = traffic != null || withinStartupGrace();
        } else {
            answered = traffic != null
                    && (traffic.isAfter(fault.at())
                        || Duration.between(fault.at(), clock.instant()).compareTo(staleness) > 0);
        }
        return answered;
    }

    private boolean withinStartupGrace() {
        final Instant startedAt = intakeStartedAt.get();
        return startedAt == null || Duration.between(startedAt, clock.instant()).compareTo(staleness) < 0;
    }

    private record Fault(String condition, Instant at) {
    }
}
