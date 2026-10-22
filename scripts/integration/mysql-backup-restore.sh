#!/usr/bin/env bash
# R03 drill: migrates and seeds a fresh MySQL fixture, backs it up with scripts/ops/mysql-backup.sh, restores it into a
# second fresh container with scripts/ops/mysql-restore.sh and compares both. Never imports .env, never touches
# unipo-mysql, the 12306 dev database or any remote host; only this run's labelled containers are created and removed.
set -euo pipefail
umask 077

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture_file="$task_root/scripts/integration/mysql-backup-restore.compose.yml"
backup_script="$task_root/scripts/ops/mysql-backup.sh"
restore_script="$task_root/scripts/ops/mysql-restore.sh"
migration_dir="$task_root/backend/src/main/resources/db/migration"
for fixture_tool in docker openssl lsof java unzip gzip awk diff cmp; do
  if ! command -v "$fixture_tool" >/dev/null 2>&1; then
    printf 'Missing required tool: %s\n' "$fixture_tool" >&2
    exit 2
  fi
done
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$task_root/scripts/ops/mysql-ops-lib.sh"

fixture_context="$(docker context show)"
fixture_endpoint="$(docker context inspect "$fixture_context" --format '{{.Endpoints.docker.Host}}')"
case "$fixture_endpoint" in
  unix://*) ;;
  *) printf 'Refusing non-local Docker context.\n' >&2; exit 2 ;;
esac
fixture_docker=(docker --context "$fixture_context")

if lsof -nP -iTCP:22316 -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'Port 22316 is already in use; no service was changed.\n' >&2
  exit 2
fi
export UNIPO_R03_MYSQL_IMAGE="${UNIPO_R03_MYSQL_IMAGE:-docker.m.daocloud.io/library/mysql:8.4}"
if ! "${fixture_docker[@]}" image inspect "$UNIPO_R03_MYSQL_IMAGE" >/dev/null 2>&1; then
  printf 'The required cached MySQL 8.4 image is missing; no image was pulled.\n' >&2
  exit 2
fi
fixture_jar="${UNIPO_R03_BACKEND_JAR:-$task_root/backend/target/biecuoguo-backend-0.1.0.jar}"
if [ ! -f "$fixture_jar" ]; then
  printf 'Backend jar not found; run mvn -DskipTests package in backend first.\n' >&2
  exit 2
fi
expected_version="$(find "$migration_dir" -maxdepth 1 -name 'V*__*.sql' -exec basename {} \; \
  | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -n 1)"

UNIPO_R03_RUN_ID="$(openssl rand -hex 8)"
UNIPO_R03_SOURCE_ROOT_PASSWORD="$(openssl rand -hex 24)"
UNIPO_R03_SOURCE_PASSWORD="$(openssl rand -hex 24)"
UNIPO_R03_TARGET_ROOT_PASSWORD="$(openssl rand -hex 24)"
export UNIPO_R03_RUN_ID UNIPO_R03_SOURCE_ROOT_PASSWORD UNIPO_R03_SOURCE_PASSWORD UNIPO_R03_TARGET_ROOT_PASSWORD
export UNIPO_R03_SOURCE_CONTAINER="unipo-r03-src-$UNIPO_R03_RUN_ID"
export UNIPO_R03_TARGET_CONTAINER="unipo-r03-dst-$UNIPO_R03_RUN_ID"
export UNIPO_R03_SCHEMA="unipo_r03_$UNIPO_R03_RUN_ID"
export UNIPO_R03_EXPECTED_VERSION="$expected_version"
fixture_project="unipo-r03-$UNIPO_R03_RUN_ID"
fixture_network="${fixture_project}_default"
fixture_compose=("${fixture_docker[@]}" compose --env-file /dev/null -f "$fixture_file" -p "$fixture_project")
restore_database="unipo_restore_$UNIPO_R03_RUN_ID"
artifact_dir="$task_root/.logs/r03/$UNIPO_R03_RUN_ID"
runtime_dir="$artifact_dir/runtime"
secret_dir="$artifact_dir/secrets"
backup_dir="$artifact_dir/backups"

for fixture_name in "$UNIPO_R03_SOURCE_CONTAINER" "$UNIPO_R03_TARGET_CONTAINER"; do
  if "${fixture_docker[@]}" container inspect "$fixture_name" >/dev/null 2>&1; then
    printf 'Fixture name already exists; refusing to reuse or remove it.\n' >&2
    exit 2
  fi
done
if "${fixture_docker[@]}" network inspect "$fixture_network" >/dev/null 2>&1 || [ -e "$artifact_dir" ]; then
  printf 'Fixture network or artifact directory already exists; refusing reuse.\n' >&2
  exit 2
fi
mkdir -p "$artifact_dir" "$runtime_dir" "$secret_dir"
chmod 700 "$artifact_dir" "$secret_dir"

fixture_started=0
fixture_source_id=""
fixture_target_id=""
cleanup_fixture() {
  fixture_result=$?
  trap - EXIT
  rm -rf -- "$runtime_dir" "$secret_dir"
  if [ "$fixture_started" = 1 ]; then
    for fixture_pair in "$UNIPO_R03_SOURCE_CONTAINER:$fixture_source_id" "$UNIPO_R03_TARGET_CONTAINER:$fixture_target_id"; do
      fixture_name="${fixture_pair%%:*}"
      fixture_known_id="${fixture_pair#*:}"
      fixture_current_id="$("${fixture_docker[@]}" container inspect "$fixture_name" --format '{{.Id}}' 2>/dev/null || true)"
      fixture_owner="$("${fixture_docker[@]}" container inspect "$fixture_name" \
        --format '{{index .Config.Labels "com.unipo.fixture.run"}}' 2>/dev/null || true)"
      if { [ -n "$fixture_current_id" ] && [ "$fixture_owner" != "$UNIPO_R03_RUN_ID" ]; } \
        || { [ -n "$fixture_known_id" ] && [ -n "$fixture_current_id" ] && [ "$fixture_current_id" != "$fixture_known_id" ]; }; then
        printf 'Fixture ownership changed; refusing cleanup.\n' >&2
        exit 2
      fi
    done
    fixture_network_id="$("${fixture_docker[@]}" network inspect "$fixture_network" --format '{{.Id}}' 2>/dev/null || true)"
    fixture_network_owner="$("${fixture_docker[@]}" network inspect "$fixture_network" \
      --format '{{index .Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
    if [ -n "$fixture_network_id" ] && [ "$fixture_network_owner" != "$fixture_project" ]; then
      printf 'Fixture network ownership changed; refusing cleanup.\n' >&2
      exit 2
    fi
    "${fixture_compose[@]}" down --timeout 10 >/dev/null 2>&1 || fixture_result=2
  fi
  unset UNIPO_R03_SOURCE_ROOT_PASSWORD UNIPO_R03_SOURCE_PASSWORD UNIPO_R03_TARGET_ROOT_PASSWORD UNIPO_OPS_MYSQL_PWD
  if [ "$fixture_result" = 0 ]; then
    printf 'R03 drill %s passed; containers removed. Evidence: %s\n' "$UNIPO_R03_RUN_ID" "$artifact_dir"
  else
    printf 'R03 drill %s FAILED (exit %s); containers removed. Evidence: %s\n' \
      "$UNIPO_R03_RUN_ID" "$fixture_result" "$artifact_dir" >&2
  fi
  exit "$fixture_result"
}
trap cleanup_fixture EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

step() {
  printf '\n== %s\n' "$*"
}

# Runs a command that must be refused: non-zero exit and the expected phrase in its output.
expect_refusal() {
  local label="$1" phrase="$2" log="$artifact_dir/refusal-$1.log" status
  shift 2
  set +e
  "$@" >"$log" 2>&1
  status=$?
  set -e
  if [ "$status" = 0 ] || ! grep -q -- "$phrase" "$log"; then
    printf 'Expected refusal %s did not happen (exit %s); see %s\n' "$label" "$status" "$log" >&2
    exit 1
  fi
  printf 'refused as expected: %s (exit %s)\n' "$label" "$status"
  printf 'refusal %s exit=%s\n' "$label" "$status" >>"$artifact_dir/summary.txt"
}

require_equal() {
  if [ "$2" != "$3" ]; then
    printf 'Check failed: %s (expected %s, got %s)\n' "$1" "$3" "$2" >&2
    exit 1
  fi
}

step "Run $UNIPO_R03_RUN_ID: starting source and target MySQL fixtures"
fixture_started=1
"${fixture_compose[@]}" up --detach --no-build --pull never source target
fixture_source_id="$("${fixture_docker[@]}" container inspect "$UNIPO_R03_SOURCE_CONTAINER" --format '{{.Id}}')"
fixture_target_id="$("${fixture_docker[@]}" container inspect "$UNIPO_R03_TARGET_CONTAINER" --format '{{.Id}}')"
for fixture_id in "$fixture_source_id" "$fixture_target_id"; do
  fixture_owner="$("${fixture_docker[@]}" container inspect "$fixture_id" \
    --format '{{index .Config.Labels "com.unipo.fixture.run"}}')"
  require_equal 'container ownership label' "$fixture_owner" "$UNIPO_R03_RUN_ID"
done
fixture_ready=0
for fixture_attempt in {1..120}; do
  # shellcheck disable=SC2016
  if "${fixture_docker[@]}" exec "$fixture_source_id" sh -eu -c '
      MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" \
        --database="$MYSQL_DATABASE" --batch --skip-column-names --execute="SELECT 1"' >/dev/null 2>&1 \
    && "${fixture_docker[@]}" exec "$fixture_target_id" sh -eu -c '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --user=root \
        --batch --skip-column-names --execute="SELECT 1"' >/dev/null 2>&1; then
    fixture_ready=1
    break
  fi
  sleep 1
done
require_equal 'fixtures ready' "$fixture_ready" 1
printf 'ready after %s polls; image %s\n' "$fixture_attempt" \
  "$("${fixture_docker[@]}" image inspect "$UNIPO_R03_MYSQL_IMAGE" --format '{{.Id}}')"
# shellcheck disable=SC2016
"${fixture_docker[@]}" exec -e UNIPO_R03_RUN_ID "$fixture_source_id" sh -eu -c '
  MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=socket --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch \
    --execute="CREATE TABLE r03_fixture_marker (run_id BINARY(8) PRIMARY KEY);
      INSERT INTO r03_fixture_marker (run_id) VALUES (0x$UNIPO_R03_RUN_ID);"'

step "Applying packaged Flyway migrations V1-V$expected_version to the source"
unzip -q "$fixture_jar" 'BOOT-INF/lib/flyway-*' 'BOOT-INF/lib/mysql-connector-j-*' 'BOOT-INF/lib/jackson-*' \
  'BOOT-INF/lib/gson-*' 'BOOT-INF/classes/db/migration/*' -d "$runtime_dir"
if ! diff -r "$runtime_dir/BOOT-INF/classes/db/migration" "$migration_dir" >/dev/null; then
  printf 'The backend jar migrations differ from the source tree; rebuild the jar first.\n' >&2
  exit 2
fi
UNIPO_R03_PORT=22316 UNIPO_R03_USER=unipo_fixture java \
  -cp "$runtime_dir/BOOT-INF/classes:$runtime_dir/BOOT-INF/lib/*" \
  "$task_root/scripts/integration/FixtureFlywayMigrate.java" 2>&1 | tee "$artifact_dir/flyway.log"
# shellcheck disable=SC2016
"${fixture_docker[@]}" exec -i "$fixture_source_id" sh -eu -c '
  MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=socket --user="$MYSQL_USER" --database="$MYSQL_DATABASE" \
    --default-character-set=utf8mb4' <"$task_root/scripts/integration/mysql-backup-restore-seed.sql"
printf 'seeded synthetic users, posts, comments, achievement progress, conversations, messages, outbox, analytics\n'

ops_docker_init
ops_credentials_init root ''
# Deterministic description of one database without its name: per-table rows and CHECKSUM TABLE, a hash of the
# schema as information_schema sees it (types, charsets, defaults, generated expressions, indexes, foreign keys,
# checks, AUTO_INCREMENT), and the awkward seeded values as hex. SHOW CREATE TABLE text is kept only as an artifact:
# after a restore MySQL renders explicit column collations with an extra CHARACTER SET, see DEVLOG 2026-10-23.
snapshot() {
  local container="$1" db="$2" tables table count_sql='' checksum_sql='' ddl_sql=''
  tables="$(ops_sql "$container" "SELECT TABLE_NAME FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '$db' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")"
  for table in $tables; do
    count_sql="${count_sql:+$count_sql UNION ALL }SELECT '$table', COUNT(*) FROM \`$db\`.\`$table\`"
    checksum_sql="${checksum_sql:+$checksum_sql, }\`$db\`.\`$table\`"
    ddl_sql="$ddl_sql SHOW CREATE TABLE \`$db\`.\`$table\`;"
  done
  printf 'tables %s\n' "$(printf '%s\n' "$tables" | wc -l | tr -d ' ')"
  ops_sql "$container" "$count_sql" | awk -F '\t' '{print "rows " $1 " " $2}'
  ops_sql "$container" "CHECKSUM TABLE $checksum_sql" | awk -F '\t' '{sub(/^[^.]*\./, "", $1); print "checksum " $1 " " $2}'
  ops_sql "$container" "$ddl_sql" >"$artifact_dir/show-create-$db.txt"
  ops_sql "$container" "SET SESSION information_schema_stats_expiry = 0;
    SELECT 'table', TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION, COALESCE(AUTO_INCREMENT, '-'), CREATE_OPTIONS
      FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$db' ORDER BY TABLE_NAME;
    SELECT 'column', TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COALESCE(COLUMN_DEFAULT, '<null>'),
        COALESCE(CHARACTER_SET_NAME, '-'), COALESCE(COLLATION_NAME, '-'), EXTRA, GENERATION_EXPRESSION
      FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '$db' ORDER BY TABLE_NAME, ORDINAL_POSITION;
    SELECT 'index', TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, COALESCE(SUB_PART, '-'), INDEX_TYPE
      FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '$db' ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
    SELECT 'key', TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION, COLUMN_NAME, COALESCE(REFERENCED_TABLE_NAME, '-'),
        COALESCE(REFERENCED_COLUMN_NAME, '-')
      FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = '$db' ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;
    SELECT 'fk', TABLE_NAME, CONSTRAINT_NAME, UPDATE_RULE, DELETE_RULE
      FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '$db' ORDER BY TABLE_NAME, CONSTRAINT_NAME;
    SELECT 'check', CONSTRAINT_NAME, CHECK_CLAUSE
      FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '$db' ORDER BY CONSTRAINT_NAME;
    SELECT 'objects', (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = '$db'),
      (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = '$db'),
      (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = '$db')" >"$artifact_dir/schema-$db.txt"
  printf 'schema_lines %s\n' "$(wc -l <"$artifact_dir/schema-$db.txt" | tr -d ' ')"
  printf 'schema_sha256 %s\n' "$(ops_sha256 "$artifact_dir/schema-$db.txt")"
  ops_sql "$container" "SELECT CONCAT('flyway latest=', MAX(CAST(version AS UNSIGNED)), ' failed=', SUM(success = 0),
    ' applied=', COUNT(*)) FROM \`$db\`.flyway_schema_history WHERE version IS NOT NULL"
  ops_sql "$container" "SELECT CONCAT('marker ', LOWER(HEX(run_id))) FROM \`$db\`.r03_fixture_marker"
  ops_sql "$container" "SELECT CONCAT('nickname ', public_uid, ' ', HEX(nickname)) FROM \`$db\`.users
    WHERE email LIKE 'r03-%' ORDER BY public_uid"
  ops_sql "$container" "SELECT CONCAT('message ', COALESCE(client_message_id, '-'), ' ', HEX(content)) FROM \`$db\`.messages
    WHERE content <> '' ORDER BY id"
  ops_sql "$container" "SELECT CONCAT('post ', HEX(content)) FROM \`$db\`.posts WHERE tags = 'r03,备份'"
  ops_sql "$container" "SELECT CONCAT('activity_mismatch ', COUNT(*)) FROM \`$db\`.conversations
    WHERE NOT (activity_at <=> COALESCE(last_message_at, created_at))"
  ops_sql "$container" "SELECT CONCAT('json ', HEX(properties_json->>'\$.note'), ' ', JSON_LENGTH(properties_json->'\$.nested'))
    FROM \`$db\`.analytics_events WHERE event_name = 'r03_drill'"
  ops_sql "$container" "SELECT CONCAT('outbox ', event_id, ' ', available_at, ' ', completed_at)
    FROM \`$db\`.message_delivery_outbox WHERE event_id LIKE 'r03%'"
}
snapshot "$fixture_source_id" "$UNIPO_R03_SCHEMA" >"$artifact_dir/source-snapshot.txt"

step 'Backup refusals'
expect_refusal backup-missing-env 'UNIPO_BACKUP_DIR is required' \
  env -u UNIPO_BACKUP_DIR UNIPO_BACKUP_DATABASE="$UNIPO_R03_SCHEMA" UNIPO_BACKUP_CONTAINER="$UNIPO_R03_SOURCE_CONTAINER" \
  bash "$backup_script"
printf '%s\n' "$UNIPO_R03_SOURCE_PASSWORD" >"$secret_dir/app-password"
chmod 644 "$secret_dir/app-password"
expect_refusal backup-loose-password-file 'mode 600 or 400' \
  env UNIPO_BACKUP_DIR="$artifact_dir/backups-app" UNIPO_BACKUP_DATABASE="$UNIPO_R03_SCHEMA" \
  UNIPO_BACKUP_CONTAINER="$UNIPO_R03_SOURCE_CONTAINER" UNIPO_BACKUP_MYSQL_USER=unipo_fixture \
  UNIPO_BACKUP_PASSWORD_FILE="$secret_dir/app-password" bash "$backup_script"

chmod 600 "$secret_dir/app-password"
# A wrong file value must fail even though the container env holds the right password for this account.
openssl rand -hex 24 >"$secret_dir/wrong-password"
chmod 600 "$secret_dir/wrong-password"
expect_refusal backup-wrong-password-file 'Access denied' \
  env UNIPO_BACKUP_DIR="$artifact_dir/backups-wrong" UNIPO_BACKUP_DATABASE="$UNIPO_R03_SCHEMA" \
  UNIPO_BACKUP_CONTAINER="$UNIPO_R03_SOURCE_CONTAINER" UNIPO_BACKUP_MYSQL_USER=unipo_fixture \
  UNIPO_BACKUP_PASSWORD_FILE="$secret_dir/wrong-password" bash "$backup_script"
require_equal 'no file kept by a failed backup' \
  "$(find "$artifact_dir/backups-wrong" -type f ! -name '.*' | wc -l | tr -d ' ')" 0

step 'Backup as the application account through a mode 600 password file'
UNIPO_BACKUP_DIR="$artifact_dir/backups-app" UNIPO_BACKUP_DATABASE="$UNIPO_R03_SCHEMA" \
  UNIPO_BACKUP_CONTAINER="$UNIPO_R03_SOURCE_CONTAINER" UNIPO_BACKUP_MYSQL_USER=unipo_fixture \
  UNIPO_BACKUP_PASSWORD_FILE="$secret_dir/app-password" bash "$backup_script" 2>&1 | tee "$artifact_dir/backup-app.log"

step 'Backup as root from the container env, with retention 2 over three planted older copies'
mkdir -p "$backup_dir"
for planted in 20200101T000000Z 20200102T000000Z 20200103T000000Z; do
  printf 'planted\n' >"$backup_dir/$UNIPO_R03_SCHEMA-$planted.sql.gz"
  printf 'planted\n' >"$backup_dir/$UNIPO_R03_SCHEMA-$planted.sql.gz.sha256"
done
printf 'keep\n' >"$backup_dir/notes.txt"
printf 'keep\n' >"$backup_dir/other_db-20200101T000000Z.sql.gz"
UNIPO_BACKUP_DIR="$backup_dir" UNIPO_BACKUP_DATABASE="$UNIPO_R03_SCHEMA" \
  UNIPO_BACKUP_CONTAINER="$UNIPO_R03_SOURCE_CONTAINER" UNIPO_BACKUP_RETENTION=2 \
  bash "$backup_script" 2>&1 | tee "$artifact_dir/backup.log"
backup_file="$(find "$backup_dir" -maxdepth 1 -name "$UNIPO_R03_SCHEMA-2*.sql.gz" ! -name '*2020010*' | head -n 1)"
[ -n "$backup_file" ] || { printf 'The backup file is missing.\n' >&2; exit 1; }
remaining="$(cd "$backup_dir" && find . -maxdepth 1 -type f ! -name '.*' -exec basename {} \; | sort | tr '\n' ' ')"
expected_remaining="$(printf '%s\n' notes.txt other_db-20200101T000000Z.sql.gz \
  "$UNIPO_R03_SCHEMA-20200103T000000Z.sql.gz" "$UNIPO_R03_SCHEMA-20200103T000000Z.sql.gz.sha256" \
  "${backup_file##*/}" "${backup_file##*/}.sha256" | sort | tr '\n' ' ')"
require_equal 'retention result' "$remaining" "$expected_remaining"
require_equal 'backup directory mode' "$(ops_file_mode "$backup_dir")" 700
require_equal 'backup file mode' "$(ops_file_mode "$backup_file")" 600
require_equal 'checksum file mode' "$(ops_file_mode "$backup_file.sha256")" 600
backup_size="$(wc -c <"$backup_file" | tr -d ' ')"
backup_digest="$(ops_sha256 "$backup_file")"
printf 'retention kept: %s\n' "$remaining"
{
  printf 'run %s\nimage %s\nflyway %s\n' "$UNIPO_R03_RUN_ID" \
    "$("${fixture_docker[@]}" image inspect "$UNIPO_R03_MYSQL_IMAGE" --format '{{.Id}}')" "$(tail -n 1 "$artifact_dir/flyway.log")"
  printf 'backup %s bytes=%s sha256=%s\nretention_kept %s\n' "${backup_file##*/}" "$backup_size" "$backup_digest" "$remaining"
} >>"$artifact_dir/summary.txt"

step 'Restore refusals'
mkdir -p "$artifact_dir/tampered"
cp "$backup_file" "$backup_file.sha256" "$artifact_dir/tampered/"
printf 'x' >>"$artifact_dir/tampered/${backup_file##*/}"
expect_refusal restore-checksum-mismatch 'Checksum mismatch' bash "$restore_script" --file "$artifact_dir/tampered/${backup_file##*/}" \
  --container "$UNIPO_R03_TARGET_CONTAINER" --database "$restore_database" --create-database
expect_refusal restore-production-name 'Refusing production-looking target' bash "$restore_script" --file "$backup_file" \
  --container "$UNIPO_R03_TARGET_CONTAINER" --database biecuoguo --create-database
expect_refusal restore-production-pattern 'name looks like production' bash "$restore_script" --file "$backup_file" \
  --container "$UNIPO_R03_TARGET_CONTAINER" --database unipo_prod_copy --create-database
expect_refusal restore-primary-database 'primary application database' bash "$restore_script" --file "$backup_file" \
  --container "$UNIPO_R03_SOURCE_CONTAINER" --database "$UNIPO_R03_SCHEMA"
expect_refusal restore-missing-database 'pass --create-database' bash "$restore_script" --file "$backup_file" \
  --container "$UNIPO_R03_TARGET_CONTAINER" --database "$restore_database"
require_equal 'no schema created by refusals' "$(ops_sql "$fixture_target_id" "SELECT COUNT(*) FROM information_schema.SCHEMATA
  WHERE SCHEMA_NAME IN ('biecuoguo', 'unipo_prod_copy', '$restore_database')")" 0

step "Restore into $UNIPO_R03_TARGET_CONTAINER/$restore_database"
bash "$restore_script" --file "$backup_file" --container "$UNIPO_R03_TARGET_CONTAINER" \
  --database "$restore_database" --create-database 2>&1 | tee "$artifact_dir/restore.log"
expect_refusal restore-non-empty 'restore only into an empty database' bash "$restore_script" --file "$backup_file" \
  --container "$UNIPO_R03_TARGET_CONTAINER" --database "$restore_database"

step 'Recovery path: the production name is accepted only with --allow-production-target'
bash "$restore_script" --file "$backup_file" --container "$UNIPO_R03_TARGET_CONTAINER" --database biecuoguo \
  --create-database --allow-production-target 2>&1 | tee "$artifact_dir/restore-allowed.log"
require_equal 'allowed restore row counts' "$(grep -o 'Row counts:.*' "$artifact_dir/restore-allowed.log")" \
  "$(grep -o 'Row counts:.*' "$artifact_dir/restore.log")"
printf 'allowed_production_name_restore %s\n' "$(grep -o 'Row counts:.*' "$artifact_dir/restore-allowed.log")" \
  >>"$artifact_dir/summary.txt"

step 'Comparing source and restored databases'
snapshot "$fixture_target_id" "$restore_database" >"$artifact_dir/restored-snapshot.txt"
if ! cmp -s "$artifact_dir/source-snapshot.txt" "$artifact_dir/restored-snapshot.txt"; then
  diff "$artifact_dir/source-snapshot.txt" "$artifact_dir/restored-snapshot.txt" >&2 || true
  printf 'Restored database differs from the source.\n' >&2
  exit 1
fi
require_equal 'restored marker' "$(grep '^marker ' "$artifact_dir/restored-snapshot.txt")" "marker $UNIPO_R03_RUN_ID"
require_equal 'restored flyway' "$(grep '^flyway ' "$artifact_dir/restored-snapshot.txt" | sed -E 's/ applied=.*//')" \
  "flyway latest=$expected_version failed=0"
require_equal 'generated activity_at' "$(grep '^activity_mismatch ' "$artifact_dir/restored-snapshot.txt")" 'activity_mismatch 0'
spot=''
for table in users posts comments achievement_progress conversations messages message_delivery_outbox; do
  spot="$spot $table=$(awk -v t="$table" '$1 == "rows" && $2 == t {print $3}' "$artifact_dir/restored-snapshot.txt")"
done
checksummed="$(grep -c '^checksum ' "$artifact_dir/restored-snapshot.txt")"
source_create="$artifact_dir/show-create-$UNIPO_R03_SCHEMA.txt"
restored_create="$artifact_dir/show-create-$restore_database.txt"
if cmp -s "$source_create" "$restored_create"; then
  show_create='identical'
elif cmp -s <(sed 's/CHARACTER SET utf8mb4 COLLATE/COLLATE/g' "$source_create") \
  <(sed 's/CHARACTER SET utf8mb4 COLLATE/COLLATE/g' "$restored_create"); then
  show_create='differs only by explicit CHARACTER SET rendering'
else
  show_create='differs beyond charset rendering (schema fingerprint still matched)'
fi
{
  printf 'restore %s\n' "$(grep 'Restore finished' "$artifact_dir/restore.log" | sed -E 's/^[^ ]+ //')"
  printf 'spot_check%s\n' "$spot"
  printf 'identical_snapshot tables=%s checksums=%s schema_lines=%s schema_sha256=%s\n' \
    "$(grep '^tables ' "$artifact_dir/restored-snapshot.txt" | cut -d' ' -f2)" "$checksummed" \
    "$(grep '^schema_lines ' "$artifact_dir/restored-snapshot.txt" | cut -d' ' -f2)" \
    "$(grep '^schema_sha256 ' "$artifact_dir/restored-snapshot.txt" | cut -d' ' -f2)"
  printf 'show_create_text %s\n' "$show_create"
} >>"$artifact_dir/summary.txt"
printf 'source == restored:%s; %s tables with matching CHECKSUM TABLE and schema\n' "$spot" "$checksummed"
printf '\n'
cat "$artifact_dir/summary.txt"
