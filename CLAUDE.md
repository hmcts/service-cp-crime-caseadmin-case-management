# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

`service-cp-crime-caseadmin-case-management` (renamed from `cp-case-management-service`) is an HMCTS Common Platform Spring Boot 4 / Java 25 service built from the HMCTS Spring Boot service template. It is currently a bare scaffold: `Application.java` plus actuator, observability, logging, JPA/Flyway/PostgreSQL and authz configuration. There is no domain code yet. Reference implementations of common patterns live in `hmcts/service-hmcts-springboot-demo` (see README).

Shared HMCTS code rules are imported via `.claude/CLAUDE.md` (gitignored, created by the `bootstrap-context` hook).

## Commands

```bash
./gradlew build                 # compile + unit tests + integration tests (build depends on `integration`)
./gradlew test                  # unit tests only (fail-fast, Mockito agent injected)
./gradlew test --tests 'uk.gov.hmcts.cp.logging.TracingIntegrationTest'   # single test class
./gradlew test --tests '*TracingIntegrationTest.someMethod'               # single test method
./gradlew integration           # starts docker/docker-compose.integration.yml (app, db, wiremock), runs src/integrationTest
./gradlew pmdMain               # PMD lint (.github/pmd-ruleset.xml); only runs when invoked explicitly by name
./gradlew jacocoTestReport
./gradlew bootRun               # local run, port 8082 (SERVER_PORT); needs Postgres on localhost:55432 (docker-compose.yml)
./gradlew pactVerificationTest  # Pact provider verification (tests tagged 'pact', needs pact.broker.* props/env)
./gradlew dependencyUpdates     # check dependency versions
./scripts/container-smoke.sh    # build the image, run it against docker-compose.yml brokers, prove one delivery from each
./gradlew -q publishPublicEvent [--args="[groupId] [eventName]"]        # publish a public event to local Artemis
./gradlew -q sendServiceBusMessage [--args="'<json>' [subject]"]        # send to the local Service Bus emulator queue
./gradlew -q sendServiceBusMessage --args=--peek-dlq                    # peek its dead-letter queue
```

- The repo is Java only: no Python scripts or tests. Tools that the smoke script needs live in the `src/smoke` source set as Java mains.

- Compiler runs with `-Xlint:unchecked -Werror`: warnings fail the build.
- Docker Compose gradle plugin manages the integration stack; `composeBuild` depends on `bootJar`.
- Version comes from `-DARTEFACT_VERSION` (defaults to `0.0.999`); `bootJar` is named `${rootProject.name}-${version}.jar`.

## Architecture

- Three test source sets: `src/test` (unit, JUnit 6 + Mockito), `src/integrationTest` (black-box HTTP tests against the dockerised app via `app.baseUrl` / `wiremock.baseUrl` system properties, WireMock stubs in `src/integrationTest/resources/wiremock`), and `src/pactVerificationTest`. `test` excludes the `pact` tag.
- Dependencies must stay in `build.gradle` (Dependabot does not track `apply from` files). `gradle/*.gradle` files (`pmd`, `test`, `jar`, `java`, `dependency`, `repositories`, `apispec-validation`) are template leftovers and are not applied by `build.gradle`; the root build file is the source of truth.
- Configuration is split across `application.yaml` (imports the others as `optional:classpath:`), `application-datasource.yml` (Postgres, `CP_CM_DATASOURCE_*` env vars), `application-authz.yml` (Drools-based authz filter using `CJSCPPUID` / `CPP-ACTION` headers, disabled unless `CP_CM_AUTHZ_ENABLED=true`; calls the usersgroups query API at `CP_CM_BASE_URL`), and `application-server-management.yml` (port, context path, actuator, OTLP tracing/metrics).
- All endpoints are served under context path `/casemanagement-service` (including `/actuator/health`); health checks in compose files, `build.gradle` and `codeql.yml` depend on it.
- Logging is JSON via `logback.xml` (logstash encoder); Application Insights agent config is in `lib/applicationinsights.json`.
- CI is GitHub Actions (`.github/workflows`: build/publish, CodeQL + ZAP scan against the running container, secrets scanner, PMD code analysis). See `docs/PIPELINE.md`.

## Naming caveats

The service/artifact name is `service-cp-crime-caseadmin-case-management`, but the context path, DB name/user (`casemanagement`), `CP_CM_*` env vars and the `casemanagement.system-user-id` property still use the old naming. Change them together if renaming further.
