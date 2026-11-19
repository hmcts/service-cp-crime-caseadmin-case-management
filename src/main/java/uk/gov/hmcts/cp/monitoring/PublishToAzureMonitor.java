package uk.gov.hmcts.cp.monitoring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts an exception type in to Azure Monitor. When an exception of an annotated type (or of a
 * subclass, the annotation is {@link Inherited}) reaches
 * {@link PublishMetricsService#publishExceptionIfEnabled}, one is added to the counter named by
 * {@link #metricName()}. Exceptions without it publish nothing: nothing goes to Azure Monitor by
 * default.
 *
 * <p>For exception types this service does not own (Spring, Azure SDK, JDK), call
 * {@link PublishMetricsService#publishException} explicitly where they are caught.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PublishToAzureMonitor {

    /** The counter the exception is added to. */
    String metricName() default PublishMetricsService.EXCEPTIONS_METRIC;
}
