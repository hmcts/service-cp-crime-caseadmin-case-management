package uk.gov.hmcts.cp.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether the Service Bus consumer can reach its queue, from what the processor has seen
 * (a lean version of yot-results-distribution's indicator).
 *
 * <p>DOWN only while a processor error is more recent than the last successful traffic and younger
 * than the staleness window. An idle queue produces no traffic, and silence is not an outage, so an
 * old error with nothing since stops counting once the window has passed.
 */
@Slf4j
public class ServiceBusHealthIndicator implements HealthIndicator {

    private static final String NONE = "none";

    private final Duration staleness;
    private final Clock clock;
    private final AtomicReference<Fault> lastFault = new AtomicReference<>();
    private final AtomicReference<Instant> lastTraffic = new AtomicReference<>();

    public ServiceBusHealthIndicator(final Duration staleness, final Clock clock) {
        this.staleness = staleness;
        this.clock = clock;
    }

    /** Records an error reported by the processor (connection, auth, missing entity...). */
    public void recordError(final String source, final Throwable failure) {
        lastFault.set(new Fault(source + ':' + failure.getClass().getSimpleName(), clock.instant()));
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
                .build();
    }

    private boolean reachable(final Fault fault, final Instant traffic) {
        return fault == null
                || traffic != null && traffic.isAfter(fault.at())
                || Duration.between(fault.at(), clock.instant()).compareTo(staleness) > 0;
    }

    private record Fault(String condition, Instant at) {
    }
}
