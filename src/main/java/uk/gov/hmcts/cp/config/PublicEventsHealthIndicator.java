package uk.gov.hmcts.cp.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import uk.gov.hmcts.cp.adapter.publicevents.DeliveryObserver;

/**
 * Reports the {@code public.event} subscription: UP while its listener container is running, plus
 * the age of the last delivery. Silence is published as an age rather than called DOWN: a quiet
 * topic is not a broken one.
 */
public class PublicEventsHealthIndicator implements HealthIndicator, DeliveryObserver {

    private static final String NONE = "none";

    private final BooleanSupplier subscriptionRunning;
    private final Clock clock;
    private final AtomicReference<Instant> lastDelivery = new AtomicReference<>();

    public PublicEventsHealthIndicator(final BooleanSupplier subscriptionRunning, final Clock clock) {
        this.subscriptionRunning = subscriptionRunning;
        this.clock = clock;
    }

    @Override
    public void recordDelivery() {
        lastDelivery.set(clock.instant());
    }

    @Override
    public Health health() {
        final boolean running = subscriptionRunning.getAsBoolean();
        final Instant delivery = lastDelivery.get();
        return (running ? Health.up() : Health.down())
                .withDetail("subscription", running ? "running" : "stopped")
                .withDetail("lastDeliveryAt", delivery == null ? NONE : delivery.toString())
                .withDetail("lastDeliveryAgeSeconds", delivery == null
                        ? NONE : Duration.between(delivery, clock.instant()).toSeconds())
                .build();
    }
}
