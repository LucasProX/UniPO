#!/usr/bin/env bash
# Runs account identity integration tests against a new local MySQL fixture. Never imports .env.
set -euo pipefail

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture_file="$task_root/scripts/integration/mysql-identity.compose.yml"
for fixture_tool in docker openssl lsof; do
  if ! command -v "$fixture_tool" >/dev/null 2>&1; then
    printf 'Missing required tool: %s\n' "$fixture_tool" >&2
    exit 2
  fi
done
if [ "$#" = 0 ] && ! command -v mvn >/dev/null 2>&1; then
  printf 'Missing required tool: mvn\n' >&2
  exit 2
fi

fixture_context="$(docker context show)"
fixture_endpoint="$(docker context inspect "$fixture_context" --format '{{.Endpoints.docker.Host}}')"
case "$fixture_endpoint" in
  unix://*) ;;
  *) printf 'Refusing non-local Docker context.\n' >&2; exit 2 ;;
esac
fixture_docker=(docker --context "$fixture_context")

if lsof -nP -iTCP:22306 -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'Port 22306 is already in use; no service was changed.\n' >&2
  exit 2
fi

export UNIPO_DP02_MYSQL_RUN_ID="$(openssl rand -hex 8)"
export UNIPO_DP02_MYSQL_CONTAINER="unipo-dp02-mysql-$UNIPO_DP02_MYSQL_RUN_ID"
export UNIPO_DP02_MYSQL_SCHEMA="unipo_dp02_$UNIPO_DP02_MYSQL_RUN_ID"
export UNIPO_DP02_MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
export UNIPO_DP02_MYSQL_PASSWORD="$(openssl rand -hex 24)"
fixture_project="unipo-dp02-mysql-$UNIPO_DP02_MYSQL_RUN_ID"
fixture_network="${fixture_project}_default"
fixture_image='docker.m.daocloud.io/library/mysql:8.4'
fixture_compose=("${fixture_docker[@]}" compose --env-file /dev/null -f "$fixture_file" -p "$fixture_project")
fixture_persistent="${UNIPO_DP02_PERSISTENT_FIXTURE:-0}"
fixture_volume="$UNIPO_DP02_MYSQL_CONTAINER-data"
if [ "$fixture_persistent" = 1 ]; then
  fixture_compose+=(-f "$task_root/scripts/integration/mysql-fault-storage.compose.yml")
  if "${fixture_docker[@]}" volume inspect "$fixture_volume" >/dev/null 2>&1; then
    printf 'The new fixture volume name already exists; refusing reuse.\n' >&2
    exit 2
  fi
fi

if "${fixture_docker[@]}" container inspect "$UNIPO_DP02_MYSQL_CONTAINER" >/dev/null 2>&1 \
  || "${fixture_docker[@]}" network inspect "$fixture_network" >/dev/null 2>&1; then
  printf 'Fixture name already exists; refusing to reuse or remove it.\n' >&2
  exit 2
fi
if ! "${fixture_docker[@]}" image inspect "$fixture_image" >/dev/null 2>&1; then
  printf 'The required cached MySQL 8.4 image is missing; no image was pulled.\n' >&2
  exit 2
fi

fixture_started=0
fixture_container_id=""
cleanup_fixture() {
  fixture_result=$?
  trap - EXIT
  if [ "$fixture_started" = 1 ]; then
    fixture_current_id="$("${fixture_docker[@]}" container inspect "$UNIPO_DP02_MYSQL_CONTAINER" \
      --format '{{.Id}}' 2>/dev/null || true)"
    fixture_owner="$("${fixture_docker[@]}" container inspect "$UNIPO_DP02_MYSQL_CONTAINER" \
      --format '{{index .Config.Labels "com.unipo.fixture.run"}}' 2>/dev/null || true)"
    fixture_network_id="$("${fixture_docker[@]}" network inspect "$fixture_network" \
      --format '{{.Id}}' 2>/dev/null || true)"
    fixture_network_owner="$("${fixture_docker[@]}" network inspect "$fixture_network" \
      --format '{{index .Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
    if { [ -n "$fixture_current_id" ] && [ "$fixture_owner" != "$UNIPO_DP02_MYSQL_RUN_ID" ]; } \
      || { [ -n "$fixture_container_id" ] && [ -n "$fixture_current_id" ] && [ "$fixture_current_id" != "$fixture_container_id" ]; } \
      || { [ -n "$fixture_network_id" ] && [ "$fixture_network_owner" != "$fixture_project" ]; }; then
      printf 'Fixture ownership changed; refusing cleanup.\n' >&2
      exit 2
    fi
    fixture_down_args=(down --timeout 10)
    if [ "$fixture_persistent" = 1 ]; then
      fixture_volume_name="$("${fixture_docker[@]}" volume inspect "$fixture_volume" --format '{{.Name}}' 2>/dev/null || true)"
      fixture_volume_owner="$("${fixture_docker[@]}" volume inspect "$fixture_volume" \
        --format '{{index .Labels "com.unipo.fixture.run"}}' 2>/dev/null || true)"
      if [ -n "$fixture_volume_name" ] && [ "$fixture_volume_owner" != "$UNIPO_DP02_MYSQL_RUN_ID" ]; then
        printf 'Fixture volume ownership changed; refusing cleanup.\n' >&2
        exit 2
      fi
      fixture_down_args+=(--volumes)
    fi
    "${fixture_compose[@]}" "${fixture_down_args[@]}" || fixture_result=2
  fi
  unset UNIPO_DP02_MYSQL_ROOT_PASSWORD UNIPO_DP02_MYSQL_PASSWORD
  exit "$fixture_result"
}
trap cleanup_fixture EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fixture_started=1
"${fixture_compose[@]}" up --detach --no-build --pull never mysql
fixture_container_id="$("${fixture_docker[@]}" container inspect "$UNIPO_DP02_MYSQL_CONTAINER" --format '{{.Id}}')"
fixture_owner="$("${fixture_docker[@]}" container inspect "$fixture_container_id" \
  --format '{{index .Config.Labels "com.unipo.fixture.run"}}')"
if [ "$fixture_owner" != "$UNIPO_DP02_MYSQL_RUN_ID" ]; then
  printf 'The new MySQL container does not have this run ownership marker.\n' >&2
  exit 2
fi
fixture_ready=0
for fixture_attempt in {1..90}; do
  if "${fixture_docker[@]}" exec "$fixture_container_id" sh -eu -c '
    export MYSQL_PWD="$MYSQL_PASSWORD"
    exec mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" \
      --batch --skip-column-names --execute="SELECT 1"
  ' >/dev/null 2>&1; then
    fixture_ready=1
    break
  fi
  sleep 1
done
if [ "$fixture_ready" != 1 ]; then
  printf 'The new MySQL fixture did not become ready.\n' >&2
  exit 2
fi
"${fixture_docker[@]}" exec "$fixture_container_id" sh -eu -c '
  export MYSQL_PWD="$MYSQL_PASSWORD"
  exec mysql --protocol=socket --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch \
    --execute="CREATE TABLE dp02_fixture_marker (run_id BINARY(8) PRIMARY KEY);
      INSERT INTO dp02_fixture_marker (run_id) VALUES (0x$UNIPO_DP02_MYSQL_RUN_ID);"
'

export UNIPO_DP02_MYSQL_INTEGRATION=1
export UNIPO_DP02_MYSQL_HOST=127.0.0.1
export UNIPO_DP02_MYSQL_PORT=22306
export UNIPO_DP02_MYSQL_USER=unipo_fixture
if [ "$#" -gt 0 ]; then
  printf 'Running the supplied command inside this fresh MySQL fixture lifecycle.\n'
  "$@"
  exit 0
fi
fixture_log_dir="$task_root/.logs/dp02/mysql-$UNIPO_DP02_MYSQL_RUN_ID"
mkdir -p "$fixture_log_dir"
fixture_log="$fixture_log_dir/mysql-identity-$UNIPO_DP02_MYSQL_RUN_ID.log"
printf 'Running account identity tests against a fresh loopback-only MySQL fixture.\n'
printf 'Test output: %s\n' "$fixture_log"
set +e
mvn -B -f "$task_root/backend/pom.xml" -Dtest=AccountSecurityMySqlIntegrationTest \
  -Dsurefire.reportNameSuffix="$UNIPO_DP02_MYSQL_RUN_ID" test 2>&1 | tee "$fixture_log"
fixture_pipeline_status=("${PIPESTATUS[@]}")
fixture_test_result=${fixture_pipeline_status[0]}
set -e
if [ "${fixture_pipeline_status[1]}" != 0 ]; then
  printf 'Could not preserve the integration test log.\n' >&2
  fixture_test_result=2
fi
fixture_report="$task_root/backend/target/surefire-reports/TEST-com.biecuoguo.auth.internal.AccountSecurityMySqlIntegrationTest-$UNIPO_DP02_MYSQL_RUN_ID.xml"
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
