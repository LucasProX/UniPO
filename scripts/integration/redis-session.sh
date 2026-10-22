#!/usr/bin/env bash
# Runs R06 against a newly created local Redis fixture. Never imports .env.
set -euo pipefail

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture_file="$task_root/scripts/integration/redis-session.compose.yml"
for fixture_tool in docker mvn openssl lsof; do
  if ! command -v "$fixture_tool" >/dev/null 2>&1; then
    printf 'Missing required tool: %s\n' "$fixture_tool" >&2
    exit 2
  fi
done

fixture_context="$(docker context show)"
fixture_endpoint="$(docker context inspect "$fixture_context" --format '{{.Endpoints.docker.Host}}')"
case "$fixture_endpoint" in
  unix://*) ;;
  *) printf 'Refusing non-local Docker context.\n' >&2; exit 2 ;;
esac
fixture_docker=(docker --context "$fixture_context")

if lsof -nP -iTCP:26379 -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'Port 26379 is already in use; no service was changed.\n' >&2
  exit 2
fi

UNIPO_DP02_RUN_ID="$(openssl rand -hex 8)"
export UNIPO_DP02_RUN_ID
export UNIPO_DP02_REDIS_CONTAINER="unipo-dp02-redis-$UNIPO_DP02_RUN_ID"
export UNIPO_DP02_REDIS_IMAGE="${UNIPO_DP02_REDIS_IMAGE:-redis:7-alpine}"
fixture_project="unipo-dp02-$UNIPO_DP02_RUN_ID"
fixture_network="${fixture_project}_default"
fixture_compose=("${fixture_docker[@]}" compose --env-file /dev/null -f "$fixture_file" -p "$fixture_project")
fixture_persistent="${UNIPO_DP02_PERSISTENT_FIXTURE:-0}"
fixture_volume="$UNIPO_DP02_REDIS_CONTAINER-data"
if [ "$fixture_persistent" = 1 ]; then
  fixture_compose+=(-f "$task_root/scripts/integration/redis-fault-storage.compose.yml")
  if "${fixture_docker[@]}" volume inspect "$fixture_volume" >/dev/null 2>&1; then
    printf 'The new fixture volume name already exists; refusing reuse.\n' >&2
    exit 2
  fi
fi

if "${fixture_docker[@]}" container inspect "$UNIPO_DP02_REDIS_CONTAINER" >/dev/null 2>&1 \
  || "${fixture_docker[@]}" network inspect "$fixture_network" >/dev/null 2>&1; then
  printf 'Fixture name already exists; refusing to reuse or remove it.\n' >&2
  exit 2
fi
if ! "${fixture_docker[@]}" image inspect "$UNIPO_DP02_REDIS_IMAGE" >/dev/null 2>&1; then
  printf 'Redis image is not cached. Pull redis:7-alpine, or set UNIPO_DP02_REDIS_IMAGE to a cached Redis 7 image.\n' >&2
  exit 2
fi

fixture_started=0
fixture_container_id=""
cleanup_fixture() {
  fixture_result=$?
  trap - EXIT
  if [ "$fixture_started" = 1 ]; then
    fixture_current_id="$("${fixture_docker[@]}" container inspect "$UNIPO_DP02_REDIS_CONTAINER" \
      --format '{{.Id}}' 2>/dev/null || true)"
    fixture_owner="$("${fixture_docker[@]}" container inspect "$UNIPO_DP02_REDIS_CONTAINER" \
      --format '{{index .Config.Labels "com.unipo.fixture.run"}}' 2>/dev/null || true)"
    fixture_network_id="$("${fixture_docker[@]}" network inspect "$fixture_network" \
      --format '{{.Id}}' 2>/dev/null || true)"
    fixture_network_owner="$("${fixture_docker[@]}" network inspect "$fixture_network" \
      --format '{{index .Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
    if { [ -n "$fixture_current_id" ] && [ "$fixture_owner" != "$UNIPO_DP02_RUN_ID" ]; } \
      || { [ -n "$fixture_container_id" ] && [ -n "$fixture_current_id" ] && [ "$fixture_current_id" != "$fixture_container_id" ]; } \
      || { [ -n "$fixture_network_id" ] && [ "$fixture_network_owner" != "$fixture_project" ]; }; then
      printf 'Fixture ownership changed; refusing cleanup.\n' >&2
      exit 2
    fi
    fixture_down_args=(down --timeout 5)
    if [ "$fixture_persistent" = 1 ]; then
      fixture_volume_name="$("${fixture_docker[@]}" volume inspect "$fixture_volume" --format '{{.Name}}' 2>/dev/null || true)"
      fixture_volume_owner="$("${fixture_docker[@]}" volume inspect "$fixture_volume" \
        --format '{{index .Labels "com.unipo.fixture.run"}}' 2>/dev/null || true)"
      if [ -n "$fixture_volume_name" ] && [ "$fixture_volume_owner" != "$UNIPO_DP02_RUN_ID" ]; then
        printf 'Fixture volume ownership changed; refusing cleanup.\n' >&2
        exit 2
      fi
      fixture_down_args+=(--volumes)
    fi
    "${fixture_compose[@]}" "${fixture_down_args[@]}" || fixture_result=2
  fi
  exit "$fixture_result"
}
trap cleanup_fixture EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fixture_started=1
"${fixture_compose[@]}" up --detach --no-build --pull never redis
fixture_container_id="$("${fixture_docker[@]}" container inspect "$UNIPO_DP02_REDIS_CONTAINER" --format '{{.Id}}')"
fixture_ready=0
for fixture_attempt in {1..40}; do
  if [ "$("${fixture_docker[@]}" exec "$UNIPO_DP02_REDIS_CONTAINER" redis-cli ping 2>/dev/null || true)" = PONG ]; then
    fixture_ready=1
    break
  fi
  sleep 0.5
done
if [ "$fixture_ready" != 1 ]; then
  printf 'The new Redis fixture did not become ready.\n' >&2
  exit 2
fi
"${fixture_docker[@]}" exec "$UNIPO_DP02_REDIS_CONTAINER" redis-cli \
  SET unipo:dp02:fixture:marker "$UNIPO_DP02_RUN_ID" >/dev/null

export UNIPO_DP02_REDIS_INTEGRATION=1
export UNIPO_DP02_REDIS_HOST=127.0.0.1
export UNIPO_DP02_REDIS_PORT=26379
if [ "$#" -gt 0 ]; then
  printf 'Running the supplied command inside this fresh Redis fixture lifecycle.\n'
  "$@"
  exit 0
fi
fixture_log_dir="$task_root/.logs/dp02/$UNIPO_DP02_RUN_ID"
mkdir -p "$fixture_log_dir"
fixture_log="$fixture_log_dir/redis-session-$UNIPO_DP02_RUN_ID.log"
printf 'Running R06 against a fresh loopback-only Redis fixture.\n'
printf 'Test output: %s\n' "$fixture_log"
set +e
mvn -B -f "$task_root/backend/pom.xml" -Dtest=RedisSessionStoreIntegrationTest \
  -Dsurefire.reportNameSuffix="$UNIPO_DP02_RUN_ID" test 2>&1 | tee "$fixture_log"
fixture_pipeline_status=("${PIPESTATUS[@]}")
fixture_test_result=${fixture_pipeline_status[0]}
set -e
if [ "${fixture_pipeline_status[1]}" != 0 ]; then
  printf 'Could not preserve the integration test log.\n' >&2
  fixture_test_result=2
fi
fixture_report="$task_root/backend/target/surefire-reports/TEST-com.biecuoguo.state.RedisSessionStoreIntegrationTest-$UNIPO_DP02_RUN_ID.xml"
if [ -f "$fixture_report" ]; then
  cp "$fixture_report" "$fixture_log_dir/"
  if ! grep -Eq 'tests="[1-9][0-9]*"' "$fixture_report" \
    || ! grep -q 'skipped="0"' "$fixture_report"; then
    printf 'The explicitly enabled integration tests were skipped or not executed.\n' >&2
    fixture_test_result=2
  fi
else
  printf 'No integration test report was produced.\n' >&2
  fixture_test_result=2
fi
exit "$fixture_test_result"
