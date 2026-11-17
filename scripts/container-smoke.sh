#!/usr/bin/env bash
# Container smoke: build the image, run it against the committed docker-compose.yml dependencies
# (Postgres, the Azure Service Bus emulator and Artemis), require it to report healthy, then prove
# REAL DELIVERY through both brokers into the running container:
#   * one JSON message on the casemanagement.requests queue  -> "Service Bus message applied"
#   * one public.progression.group-prosecution-cases-created -> "Group prosecution cases created"
# Modelled on service-cp-crime-yot-results-distribution's scripts/container-smoke.sh (which stops at
# readiness); run locally and by the Container-Smoke job in .github/workflows/ci-build-publish.yml.
#
# Needs Docker and python3. On Apple Silicon, Docker Desktop must have "Use Rosetta for x86_64/amd64
# emulation" switched on: the emulator's SQL Server image is amd64-only and crashes without it.
set -euo pipefail

readonly HEALTH_BUDGET_SECONDS=180
readonly DELIVERY_BUDGET_SECONDS=120
readonly SERVER_PORT=8082
readonly HEALTH_URL="http://localhost:${SERVER_PORT}/casemanagement-service/actuator/health"
readonly PROJECT_NAME="casemanagement-smoke"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

compose() {
  docker compose --project-name "$PROJECT_NAME" "$@"
}

log() {
  printf '[container-smoke] %s\n' "$1"
}

teardown() {
  local status=$?
  log "tearing down"
  compose logs --no-color --tail 80 service-cp-crime-caseadmin-case-management || true
  if ! compose down --volumes --remove-orphans; then
    log "FAIL: teardown left containers, networks or volumes behind"
    [ "$status" -eq 0 ] && status=1
  fi
  exit "$status"
}
trap teardown EXIT

app_logs() {
  compose logs --no-color service-cp-crime-caseadmin-case-management 2>/dev/null
}

wait_for() {
  local description=$1 budget=$2 check=$3 waited=0
  log "waiting for ${description} (budget ${budget}s)"
  until eval "$check"; do
    if [ "$waited" -ge "$budget" ]; then
      log "FAIL: ${description} not seen within ${budget}s"
      return 1
    fi
    sleep 3
    waited=$((waited + 3))
  done
  log "OK: ${description}"
}

log "building the application jar"
rm -f build/libs/*.jar
./gradlew bootJar -q
JAR_FILENAME=$(basename "$(ls build/libs/*.jar | grep -v plain | head -n1)")
export JAR_FILENAME JAR_FILE_PATH=build/libs SERVER_PORT

log "starting the brokers and the database"
compose up --detach db servicebus-emulator-mssql servicebus-emulator artemis

log "building and starting the application image (${JAR_FILENAME})"
compose up --detach --build service-cp-crime-caseadmin-case-management

# Not the servicebus component yet: like yot's, it reports DOWN once a consumer has gone a whole
# staleness window without ever hearing from the broker, which an empty queue legitimately does. It
# is asserted after the message below, when the broker has answered.
wait_for "the application live and the public.event subscription UP" "$HEALTH_BUDGET_SECONDS" \
  "curl --silent --fail --max-time 3 '$HEALTH_URL/liveness' | grep -q '\"status\":\"UP\"' \
   && curl --silent --fail --max-time 3 '$HEALTH_URL/publicEvents' | grep -q '\"status\":\"UP\"'"

log "publishing a public event to Artemis"
GROUP_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
python3 scripts/publish-public-event.py "$GROUP_ID"
wait_for "the public event applied in the container" "$DELIVERY_BUDGET_SECONDS" \
  "app_logs | grep -q 'Group prosecution cases created in progression. groupId=${GROUP_ID}'"

log "sending a message to the Service Bus emulator"
VENV=build/smoke-venv
[ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet azure-servicebus
MESSAGE_ID=$("$VENV/bin/python" scripts/send-servicebus-message.py "{\"groupId\":\"${GROUP_ID}\"}" smoke.test \
  | sed -n 's/^sent messageId=\([^ ]*\).*/\1/p')
wait_for "the Service Bus message applied in the container" "$DELIVERY_BUDGET_SECONDS" \
  "app_logs | grep -q 'Service Bus message applied. messageId=${MESSAGE_ID}'"
wait_for "the servicebus health component UP" 30 \
  "curl --silent --fail --max-time 3 '$HEALTH_URL/servicebus' | grep -q '\"status\":\"UP\"'"

log "PASS: started healthy and consumed one message from each broker"
