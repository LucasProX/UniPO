#!/usr/bin/env bash
# DP15: exclusively use a new ownership-checked local MySQL fixture.
set -euo pipefail

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "${1:-}" != --inside-fixture ]; then
  if [ "$#" != 0 ]; then
    printf 'Usage: bash scripts/integration/message-pagination.sh\n' >&2
    exit 2
  fi
  exec bash "$task_root/scripts/integration/mysql-identity.sh" bash "$task_root/scripts/integration/message-pagination.sh" --inside-fixture
fi
if [ "$#" != 1 ] || [ "${UNIPO_DP02_MYSQL_INTEGRATION:-}" != 1 ] \
  || [ "${UNIPO_DP02_MYSQL_HOST:-}" != 127.0.0.1 ] \
  || [ "${UNIPO_DP02_MYSQL_PORT:-}" != 22306 ] \
  || [[ ! "${UNIPO_DP02_MYSQL_RUN_ID:-}" =~ ^[0-9a-f]{16}$ ]]; then
  printf 'Refusing execution outside the new fixture lifecycle.\n' >&2
  exit 2
fi
export UNIPO_DP15_MYSQL_INTEGRATION=1
fixture_run="$UNIPO_DP02_MYSQL_RUN_ID"
fixture_log_dir="$task_root/.logs/dp15/mysql-$fixture_run"
mkdir -p "$fixture_log_dir"
fixture_log="$fixture_log_dir/message-pagination-$fixture_run.log"
set +e
mvn -B -ntp -f "$task_root/backend/pom.xml" -Dtest=MessagePaginationMySqlIntegrationTest \
  -Dsurefire.reportNameSuffix="$fixture_run" test 2>&1 | tee "$fixture_log"
fixture_pipeline_status=("${PIPESTATUS[@]}")
set -e
fixture_result=${fixture_pipeline_status[0]}
if [ "${fixture_pipeline_status[1]}" != 0 ]; then fixture_result=2; fi
fixture_report="$task_root/backend/target/surefire-reports/TEST-com.biecuoguo.message.internal.MessagePaginationMySqlIntegrationTest-$fixture_run.xml"
if [ ! -f "$fixture_report" ]; then
  printf 'No DP15 test report was generated.\n' >&2
  exit 2
fi
cp "$fixture_report" "$fixture_log_dir/"
if ! grep -q 'tests="13"' "$fixture_report" || ! grep -q 'skipped="0"' "$fixture_report"; then
  printf 'All 13 explicitly enabled DP15 tests must execute without skips.\n' >&2
  fixture_result=2
fi
exit "$fixture_result"
