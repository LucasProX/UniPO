#!/usr/bin/env bash
# DP14: use a newly owned MySQL lifecycle; never import .env or call external transports.
set -euo pipefail

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "${1:-}" != --inside-fixture ]; then
  if [ "$#" != 0 ]; then
    printf 'Usage: bash scripts/integration/outbox-delivery.sh\n' >&2
    exit 2
  fi
  exec bash "$task_root/scripts/integration/mysql-identity.sh" bash "$task_root/scripts/integration/outbox-delivery.sh" --inside-fixture
fi
if [ "$#" != 1 ] || [ "${UNIPO_DP02_MYSQL_INTEGRATION:-}" != 1 ] \
  || [ "${UNIPO_DP02_MYSQL_HOST:-}" != 127.0.0.1 ] \
  || [ "${UNIPO_DP02_MYSQL_PORT:-}" != 22306 ] \
  || [[ ! "${UNIPO_DP02_MYSQL_RUN_ID:-}" =~ ^[0-9a-f]{16}$ ]]; then
  printf 'Refusing to run outside this fresh MySQL fixture lifecycle.\n' >&2
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  printf 'Missing required tool: mvn\n' >&2
  exit 2
fi

export UNIPO_DP14_OUTBOX_MYSQL_INTEGRATION=1
fixture_run="$UNIPO_DP02_MYSQL_RUN_ID"
fixture_log_dir="$task_root/.logs/dp14/mysql-$fixture_run"
mkdir -p "$fixture_log_dir"
fixture_log="$fixture_log_dir/outbox-delivery-$fixture_run.log"
printf 'Running DP14 durable outbox, worker and bounded connection-pool invariants.\n'
printf 'Test output: %s\n' "$fixture_log"
set +e
mvn -B -ntp -f "$task_root/backend/pom.xml" -Dtest=MessageOutboxMySqlIntegrationTest \
  -Dsurefire.reportNameSuffix="$fixture_run" test 2>&1 | tee "$fixture_log"
fixture_pipeline_status=("${PIPESTATUS[@]}")
fixture_test_result=${fixture_pipeline_status[0]}
set -e
if [ "${fixture_pipeline_status[1]}" != 0 ]; then
  printf 'Could not preserve the integration test log.\n' >&2
  fixture_test_result=2
fi
fixture_report="$task_root/backend/target/surefire-reports/TEST-com.biecuoguo.message.internal.MessageOutboxMySqlIntegrationTest-$fixture_run.xml"
if [ -f "$fixture_report" ]; then
  cp "$fixture_report" "$fixture_log_dir/"
  if ! grep -q 'tests="18"' "$fixture_report" || ! grep -q 'skipped="0"' "$fixture_report"; then
    printf 'The explicitly enabled 18 integration tests were skipped or not all executed.\n' >&2
    fixture_test_result=2
  fi
else
  printf 'No integration test report was produced.\n' >&2
  fixture_test_result=2
fi
exit "$fixture_test_result"
