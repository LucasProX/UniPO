#!/usr/bin/env bash
# SMS gates run only inside the independent MySQL fixture. No Boot startup or .env import.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "${1:-}" != --inside-fixture ]; then
  exec bash "$task_root/scripts/integration/mysql-identity.sh" bash "$task_root/scripts/integration/sms-login.sh" --inside-fixture
fi
if [ "${UNIPO_DP02_MYSQL_INTEGRATION:-}" != 1 ] || [ "${UNIPO_DP02_MYSQL_PORT:-}" != 22306 ]; then
  printf 'The SMS integration test requires the owned MySQL fixture.\n' >&2
  exit 2
fi
fixture_test_class=SmsLoginMySqlIntegrationTest
fixture_log_dir="$task_root/.logs/dp02/sms-$UNIPO_DP02_MYSQL_RUN_ID"
mkdir -p "$fixture_log_dir"
printf 'Running controlled SMS login interleavings against a fresh MySQL fixture.\n'
printf 'Results: %s\n' "$fixture_log_dir"
set +e
mvn -B -f "$task_root/backend/pom.xml" -Dtest="$fixture_test_class" \
  -Dsurefire.reportNameSuffix="$UNIPO_DP02_MYSQL_RUN_ID" test 2>&1 | tee "$fixture_log_dir/sms-login.log"
fixture_pipeline_status=("${PIPESTATUS[@]}")
fixture_test_result=${fixture_pipeline_status[0]}
set -e
if [ "${fixture_pipeline_status[1]}" != 0 ]; then fixture_test_result=2; fi
fixture_report="$task_root/backend/target/surefire-reports/TEST-com.biecuoguo.auth.internal.$fixture_test_class-$UNIPO_DP02_MYSQL_RUN_ID.xml"
if [ -f "$fixture_report" ]; then
  cp "$fixture_report" "$fixture_log_dir/"
  if ! grep -Eq 'tests="[1-9][0-9]*"' "$fixture_report" || ! grep -q 'skipped="0"' "$fixture_report"; then
    printf 'The explicitly enabled SMS tests did not execute.\n' >&2
    fixture_test_result=2
  fi
else
  printf 'No SMS test report was produced.\n' >&2
  fixture_test_result=2
fi
exit "$fixture_test_result"
