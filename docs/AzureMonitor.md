# Azure Monitor (Application Insights) custom metrics

The service can send custom metrics to Azure Monitor, so dashboards and alerts can be built on them
in Application Insights. It follows the pattern of `cp-ai-rag-service`'s `AzureMonitorService`
(OpenTelemetry SDK + `azure-monitor-opentelemetry-autoconfigure`), adapted for Spring Boot.

**Nothing is sent by default.** Two things must both be true for a metric to leave the service:

1. Azure Monitor is enabled for the environment (`CP_CM_AZURE_MONITOR_ENABLED=true`), and
2. code chose to publish it: an explicit call, or an exception type annotated with
   `@PublishToAzureMonitor`.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `CP_CM_AZURE_MONITOR_ENABLED` | `false` | Master switch. When false every publish is a no-op. |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | – | The connection string. **In AKS this is already provided**: mounted from Key Vault by the Secrets Store CSI driver (the platform convention, as in yot-results-distribution). |
| `CP_CM_AZURE_INSIGHTS_CONNECTION_STRING` | – | Optional override of the above, e.g. a dev resource for a local run. Start-up fails if enabled with neither set. |
| `CP_CM_AZURE_MONITOR_EXPORT_INTERVAL` | `60s` | How often collected metrics are sent. |

In AKS, enabling metrics for an environment is one line in its values file:
`CP_CM_AZURE_MONITOR_ENABLED: "true"`. The connection string needs no new secret.

Settings live in `src/main/resources/application-azure-monitor.yml`. Metrics appear in Application
Insights with `cloud_RoleName` = `spring.application.name`.

### Application Insights resource per environment

> **TODO:** placeholder values. Replace with the real resource the AKS
> `APPLICATIONINSIGHTS_CONNECTION_STRING` secret points at, once confirmed with the platform team.
> Alert rules, the dashboard (Terraform) and alert recipients are still to be done and will use
> these names.

| Environment | App Insights name | Resource group | Metrics enabled |
|---|---|---|---|
| sit | `TODO-appinsights-sit` | `TODO-rg-sit` | TODO |
| nft | `TODO-appinsights-nft` | `TODO-rg-nft` | TODO |
| prp | `TODO-appinsights-prp` | `TODO-rg-prp` | TODO |
| prx | `TODO-appinsights-prx` | `TODO-rg-prx` | TODO |
| prd | `TODO-appinsights-prd` | `TODO-rg-prd` | TODO |

## Code

All classes are in `uk.gov.hmcts.cp.monitoring`.

| Class | Role |
|---|---|
| `AzureMonitorService` | Generic API (same names as rag): `publishCounter`, `publishHistogramScore`, `publishDuration`. Never throws. |
| `PublishMetricsService` | Case-management metrics: `publishEvent`, `publishExceptionIfEnabled`, `publishException`, `publishMessageSettlement`. |
| `@PublishToAzureMonitor` | Opts an exception type (and its subclasses) in. Optional `metricName`. |
| `AzureMonitorExporter` / `AzureMonitorConfig` | Private OpenTelemetry SDK, metrics only (traces, logs and Live Metrics off; the SDK's own "statsbeat" health telemetry goes to Microsoft, not to your resource). Never global, never an `OpenTelemetry` bean, so Spring Boot's own tracing SDK is untouched. |

### Send a business event

```java
publishMetricsService.publishEvent("case_management_group_case_created",
        "Group cases created", Map.of("source", "rest"));
```

### Send an exception: opt in by annotation

```java
@PublishToAzureMonitor                       // or @PublishToAzureMonitor(metricName = "case_management_progression_failures")
public class ProgressionCommandRejectedException extends RuntimeException { ... }
```

`GlobalExceptionHandler` (REST) and `CaseManagementMessageListener` (Service Bus) offer every
exception they handle to `publishExceptionIfEnabled`; only annotated types are sent. Anywhere else,
call it yourself:

```java
catch (SomeOwnException e) {
    publishMetricsService.publishExceptionIfEnabled("progression", e, null);
}
```

### Send an exception you cannot annotate (Spring, Azure SDK, JDK types)

```java
catch (HttpServerErrorException e) {
    publishMetricsService.publishException("progression", e, e.getStatusCode());
}
```

### Durations and scores

```java
azureMonitorService.publishDuration("case_management_progression_call_duration",
        "Progression call latency", Duration.between(start, Instant.now()), Map.of("operation", "remove_case"));
```

### Rules

- Name metrics `case_management_<thing>` (snake_case, as in rag).
- Dimension values must come from a small fixed set (types, statuses, outcomes). **Never** put
  ids or personal data in a dimension: every distinct value is a new series, and costs money.
- Dimensions with a null value are dropped.

## What is sent today

| Metric | When | Dimensions |
|---|---|---|
| `case_management_exceptions` | `ProgressionCommandRejectedException` or `SystemUserNotConfiguredException` reaches the REST handler or the Service Bus listener | `source` (`rest`/`servicebus`), `exception_type`, `http_status` (REST only) |
| `case_management_message_settlements` | This listener dead-letters a Service Bus message (and the broker accepted it) | `outcome` (`deadletter`), `reason` (`VALIDATION`, ...) |

Limits to know when building alerts:

- **Broker-side dead-letters are not counted.** A message that keeps failing is abandoned and
  redelivered until Service Bus itself moves it to the dead-letter queue after `MaxDeliveryCount`;
  the app never sees that move. Alert on the Service Bus namespace's own dead-letter metric for it.
- **Service Bus exceptions are counted per delivery attempt.** An opted-in exception on a message
  that is redelivered is counted each time, so one message can count up to `MaxDeliveryCount`
  times. Compare `source=servicebus` as "failed attempts", not "failed messages".
- An opted-in exception wrapped in another exception is still counted, under its own type.

## Querying, dashboards and alerts

Application Insights > Logs:

```kusto
customMetrics
| where timestamp > ago(1h) and name startswith "case_management"
| extend source = tostring(customDimensions.source),
         exception_type = tostring(customDimensions.exception_type),
         http_status = tostring(customDimensions.http_status)
| summarize count = sum(valueSum) by bin(timestamp, 5m), name, source, exception_type, http_status
| render timechart
```

- **Alert**: create a log search alert rule on the query above (e.g. `count > 0` over 5 minutes for
  `case_management_exceptions`), or use Metrics explorer > custom metrics.
- To split or alert by dimensions in Metrics explorer, enable *custom metric dimensions* under
  Application Insights > Usage and estimated costs > Custom metrics.

## Moving to Entra ID (not in use yet)

Ingestion is currently authorised by the key inside the connection string. To move to Entra ID
(managed identity) later:

1. In `AzureMonitorExporter`, replace `AzureMonitorAutoConfigure.customize(builder, connectionString)`
   with the commented-out `AzureMonitorAutoConfigureOptions` version beside it, which adds
   `.credential(new DefaultAzureCredentialBuilder().build())`. The connection string stays, because it
   carries the ingestion endpoint.
2. Grant the pod's workload identity the **Monitoring Metrics Publisher** role on the Application
   Insights resource.
3. Deploy, confirm metrics arrive, and only then disable local authentication on the resource.
   Disabling it first would silently drop every metric.

## Testing

| Level | Where | What it proves |
|---|---|---|
| Unit | `src/test/.../monitoring/*Test`, `GlobalExceptionHandlerMetricsTest`, `CaseManagementMessageListenerTest` | Real OpenTelemetry SDK with an in-memory reader (`InMemoryAzureMonitor`): names, values, units, dimensions, the opt-in rules, failure isolation. |
| Wiring | `AzureMonitorConfigTest` | Enabled/disabled/missing connection string; Spring Boot's own `OpenTelemetry` bean is not replaced. |
| Exporter | `AzureMonitorExportIntegrationTest` | The real Azure exporter posts Application Insights' ingestion format to an in-process WireMock: only selected metrics, only `MetricData`. |
| Black box | `src/integrationTest/.../AzureMonitorHttpLiveTest` (`./gradlew integration`) | Real HTTP calls to the dockerised app; its telemetry goes to the compose WireMock (`POST /v2.1/track`). A Progression rejection is sent; a 404 is not. |

Locally against a real resource:

```bash
export CP_CM_AZURE_MONITOR_ENABLED=true
export CP_CM_AZURE_INSIGHTS_CONNECTION_STRING='InstrumentationKey=...;IngestionEndpoint=...'
export CP_CM_AZURE_MONITOR_EXPORT_INTERVAL=10s
./gradlew bootRun
```

Trigger an opted-in exception, then run the KQL above after a minute or two.
