package uk.gov.hmcts.cp.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;

/**
 * Case management's metrics, on top of {@link AzureMonitorService} (as cp-ai-rag-service's
 * PublishScoreService is). Every method is an explicit choice to send something; nothing is sent
 * otherwise.
 *
 * <ul>
 *   <li>{@link #publishEvent}: any business event, from wherever it happens;</li>
 *   <li>{@link #publishExceptionIfEnabled}: an exception, only if its type carries
 *       {@link PublishToAzureMonitor}. Generic handlers call this for every exception they see;</li>
 *   <li>{@link #publishException}: an exception, unconditionally, for types that cannot be annotated;</li>
 *   <li>{@link #publishMessageSettlement}: a Service Bus settlement outcome.</li>
 * </ul>
 */
@Service
public class PublishMetricsService {

    /** Default counter for exceptions. */
    public static final String EXCEPTIONS_METRIC = "case_management_exceptions";
    /** Counter for Service Bus settlements. */
    public static final String MESSAGE_SETTLEMENTS_METRIC = "case_management_message_settlements";

    /** Where an exception or event was seen: rest, servicebus, progression, ... */
    public static final String DIMENSION_SOURCE = "source";
    /** Simple class name of the exception. */
    public static final String DIMENSION_EXCEPTION_TYPE = "exception_type";
    /** HTTP status returned (REST) or received (upstream), when there is one. */
    public static final String DIMENSION_HTTP_STATUS = "http_status";
    /** complete, abandon or deadletter. */
    public static final String DIMENSION_OUTCOME = "outcome";
    /** Why a message was not completed, e.g. VALIDATION or UNEXPECTED_FAILURE. */
    public static final String DIMENSION_REASON = "reason";

    private static final int MAX_CAUSE_DEPTH = 10;
    private static final String EXCEPTIONS_DESCRIPTION = "Exceptions selected for Azure Monitor";
    private static final String SETTLEMENTS_DESCRIPTION = "Service Bus message settlements";

    private final AzureMonitorService azureMonitorService;

    /** Creates the service. */
    public PublishMetricsService(final AzureMonitorService azureMonitorService) {
        this.azureMonitorService = azureMonitorService;
    }

    /** Adds one to the counter {@code metricName} for a business event. */
    public void publishEvent(final String metricName, final String description, final Map<String, String> dimensions) {
        azureMonitorService.publishCounter(metricName, description, dimensions);
    }

    /**
     * Publishes the exception if its type (or a supertype) is annotated with
     * {@link PublishToAzureMonitor}, to the counter the annotation names; otherwise does nothing.
     * The cause chain is searched too (up to {@value #MAX_CAUSE_DEPTH} levels), so an opted-in
     * exception wrapped by a proxy or another exception is still published, under its own type.
     *
     * @return whether an exception was published
     */
    public boolean publishExceptionIfEnabled(final String source, final Throwable exception, final HttpStatusCode status) {
        Throwable optedIn = null;
        Throwable current = exception;
        for (int depth = 0; optedIn == null && current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current.getClass().isAnnotationPresent(PublishToAzureMonitor.class)) {
                optedIn = current;
            }
            current = current.getCause();
        }
        if (optedIn != null) {
            publish(optedIn.getClass().getAnnotation(PublishToAzureMonitor.class).metricName(),
                    source, optedIn, status);
        }
        return optedIn != null;
    }

    /**
     * Publishes the exception to {@value #EXCEPTIONS_METRIC} whatever its type, for exceptions that
     * cannot be annotated (framework or SDK types). {@code status} may be null.
     */
    public void publishException(final String source, final Throwable exception, final HttpStatusCode status) {
        if (exception != null) {
            publish(EXCEPTIONS_METRIC, source, exception, status);
        }
    }

    /** Adds one to {@value #MESSAGE_SETTLEMENTS_METRIC}. {@code reason} may be null (e.g. for complete). */
    public void publishMessageSettlement(final String outcome, final String reason) {
        final Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put(DIMENSION_OUTCOME, outcome);
        dimensions.put(DIMENSION_REASON, reason);
        azureMonitorService.publishCounter(MESSAGE_SETTLEMENTS_METRIC, SETTLEMENTS_DESCRIPTION, dimensions);
    }

    private void publish(final String metricName, final String source, final Throwable exception,
                         final HttpStatusCode status) {
        final Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put(DIMENSION_SOURCE, source);
        dimensions.put(DIMENSION_EXCEPTION_TYPE, typeName(exception));
        dimensions.put(DIMENSION_HTTP_STATUS, status == null ? null : String.valueOf(status.value()));
        azureMonitorService.publishCounter(metricName, EXCEPTIONS_DESCRIPTION, dimensions);
    }

    /** Simple class name; the full name for anonymous or local classes, whose simple name is empty. */
    private static String typeName(final Throwable exception) {
        final String simple = exception.getClass().getSimpleName();
        return simple.isEmpty() ? exception.getClass().getName() : simple;
    }
}
