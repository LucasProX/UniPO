#!/usr/bin/env bash
# Restores one mysql-backup.sh dump into an explicitly named, empty database of a local MySQL container.
# The checksum is verified before anything touches MySQL. See docs/DEPLOY-SERVER.md §7.
set -euo pipefail
umask 077

ops_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$ops_dir/mysql-ops-lib.sh"

usage() {
  cat <<'EOF'
Usage: mysql-restore.sh --file DUMP.sql.gz --container NAME --database NAME [options]

  --file PATH                 dump written by mysql-backup.sh; PATH.sha256 must sit next to it
  --container NAME            running local MySQL container to restore into
  --database NAME             target database; must be empty or missing
  --create-database           create the target database when it does not exist
  --allow-production-target   required for unipo-mysql, biecuoguo, *prod*/*live* names and the
                              container's own MYSQL_DATABASE
  --mysql-user NAME           account used for the restore (default root)
  --password-file PATH        mode 600/400 file with that account's password; without it the password of
                              root or the image's MYSQL_USER is read inside the container
EOF
}

dump_file=''
container=''
database=''
create_database=0
allow_production=0
mysql_user='root'
password_file=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --file | --container | --database | --mysql-user | --password-file)
      [ "$#" -ge 2 ] || ops_fail "$1 needs a value."
      case "$1" in
        --file) dump_file="$2" ;;
        --container) container="$2" ;;
        --database) database="$2" ;;
        --mysql-user) mysql_user="$2" ;;
        --password-file) password_file="$2" ;;
      esac
      shift 2
      ;;
    --create-database) create_database=1; shift ;;
    --allow-production-target) allow_production=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; ops_fail "Unknown argument: $1" ;;
  esac
done
[ -n "$dump_file" ] || ops_fail '--file is required.'
[ -n "$container" ] || ops_fail '--container is required; there is no default restore target.'
[ -n "$database" ] || ops_fail '--database is required; there is no default restore target.'
ops_is_container_name "$container" || ops_fail '--container is not a valid container name.'
ops_is_identifier "$database" || ops_fail '--database must be a plain identifier.'

ops_require_tools docker gzip awk tr
if [ ! -f "$dump_file" ] || [ -L "$dump_file" ]; then
  ops_fail 'The dump must be a regular file.'
fi
case "$dump_file" in
  *.sql.gz) ;;
  *) ops_fail 'The dump must be a .sql.gz file written by mysql-backup.sh.' ;;
esac
sum_file="$dump_file.sha256"
[ -f "$sum_file" ] || ops_fail "Missing checksum file $sum_file; refusing an unverifiable restore."

# 1. Integrity before anything else: the sidecar must name this file and match its bytes.
read -r expected_digest expected_name extra <"$sum_file" || true
dump_name="${dump_file##*/}"
if [ -n "${extra:-}" ] || [ "${expected_name:-}" != "$dump_name" ] \
  || ! [[ "${expected_digest:-}" =~ ^[0-9a-f]{64}$ ]]; then
  ops_fail 'The checksum file is malformed or names another dump.'
fi
actual_digest="$(ops_sha256 "$dump_file")"
[ "$actual_digest" = "$expected_digest" ] || ops_fail 'Checksum mismatch; the dump is damaged or was replaced. Nothing was restored.'
scan="$(ops_scan_dump "$dump_file")" || ops_fail 'The dump is not a readable gzip stream.'
case "$scan" in
  '0 complete') ;;
  '0 truncated') ops_fail 'The dump has no completion trailer; refusing a partial restore.' ;;
  *) ops_fail 'The dump switches or drops databases; only mysql-backup.sh dumps are accepted.' ;;
esac
ops_log "Checksum verified: $dump_name (sha256 $actual_digest)."

ops_docker_init
ops_require_running_container "$container"
ops_credentials_init "$mysql_user" "$password_file"
restore_started=0
finish() {
  local result=$?
  trap - EXIT
  unset UNIPO_OPS_MYSQL_PWD
  if [ "$result" != 0 ] && [ "$restore_started" = 1 ]; then
    printf '%s Restore failed; %s/%s may be partially loaded. Drop and recreate it before retrying.\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$container" "$database" >&2
  fi
  exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 2. Production guard. A drill belongs in a throwaway container, never next to the live schema.
database_lower="$(printf '%s' "$database" | tr '[:upper:]' '[:lower:]')"
# shellcheck disable=SC2016
primary_database="$("${ops_docker[@]}" exec "$container" sh -c 'printf "%s" "${MYSQL_DATABASE:-}"')"
production_reasons=''
[ "$container" != unipo-mysql ] || production_reasons="$production_reasons container unipo-mysql hosts the deployed databases;"
[ "$database_lower" != biecuoguo ] || production_reasons="$production_reasons biecuoguo is the production database name;"
if [[ "$database_lower" =~ (^|_)(prod|production|live)(_|$) ]]; then
  production_reasons="$production_reasons the name looks like production;"
fi
if [ -n "$primary_database" ] && [ "$primary_database" = "$database" ]; then
  production_reasons="$production_reasons it is the container's primary application database;"
fi
if [ -n "$production_reasons" ] && [ "$allow_production" != 1 ]; then
  ops_fail "Refusing production-looking target $container/$database:$production_reasons pass --allow-production-target only for a real recovery."
fi
[ -z "$production_reasons" ] || ops_log "Production target explicitly allowed:$production_reasons"

# 3. The target must be empty, so a restore never merges into or silently replaces live tables.
exists="$(ops_sql "$container" "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$database'")"
if [ "$exists" = 0 ]; then
  [ "$create_database" = 1 ] || ops_fail "Database $database does not exist; pass --create-database to create it."
  ops_sql "$container" "CREATE DATABASE \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >/dev/null
  ops_log "Created empty database $database."
else
  objects="$(ops_sql "$container" "SELECT
      (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$database')
    + (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = '$database')
    + (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = '$database')")"
  [ "$objects" = 0 ] || ops_fail "Database $database already has $objects objects; restore only into an empty database."
fi

# 4. Stream the verified dump. mysql stops at the first error and the pipeline fails with it.
ops_log "Restoring $dump_name into $container/$database as $ops_mysql_user."
started="$(date +%s)"
restore_started=1
# shellcheck disable=SC2016
gzip -dc -- "$dump_file" | "${ops_docker[@]}" exec -i "${ops_exec_env[@]}" "$container" sh -c "$ops_container_auth"'
  exec mysql --protocol=socket --user="$UNIPO_OPS_MYSQL_USER" --default-character-set=utf8mb4 --database="$1"
' sh "$database"
ops_log "Restore finished in $(( $(date +%s) - started ))s."

# 5. Facts an operator compares with the source before switching traffic.
tables="$(ops_sql "$container" "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$database'")"
ops_log "Restored tables: $tables."
has_history="$(ops_sql "$container" "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$database' AND TABLE_NAME = 'flyway_schema_history'")"
if [ "$has_history" = 1 ]; then
  ops_log "Flyway: $(ops_sql "$container" "SELECT CONCAT('latest version ', COALESCE(MAX(CAST(version AS UNSIGNED)), 0),
      ', failed migrations ', COALESCE(SUM(success = 0), 0)) FROM \`$database\`.flyway_schema_history WHERE version IS NOT NULL")."
fi
counts=''
for table in users posts comments achievement_progress conversations messages; do
  present="$(ops_sql "$container" "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$database' AND TABLE_NAME = '$table'")"
  [ "$present" = 1 ] || continue
  counts="$counts $table=$(ops_sql "$container" "SELECT COUNT(*) FROM \`$database\`.\`$table\`")"
done
[ -z "$counts" ] || ops_log "Row counts:$counts."
