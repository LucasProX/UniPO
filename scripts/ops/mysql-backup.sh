#!/usr/bin/env bash
# Consistent, checksummed, gzip-compressed dump of one database in a local MySQL container, with count-based retention.
# Configuration comes only from the environment, so a cron line shows no password. See docs/DEPLOY-SERVER.md §7.
set -euo pipefail
umask 077

ops_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$ops_dir/mysql-ops-lib.sh"

usage() {
  cat <<'EOF'
Usage: UNIPO_BACKUP_DIR=/var/backups/unipo/mysql UNIPO_BACKUP_DATABASE=biecuoguo mysql-backup.sh

Required:
  UNIPO_BACKUP_DIR            absolute target directory (created with mode 700)
  UNIPO_BACKUP_DATABASE       database to dump

Optional:
  UNIPO_BACKUP_CONTAINER      MySQL container (default unipo-mysql)
  UNIPO_BACKUP_RETENTION      newest copies of this database to keep (default 14)
  UNIPO_BACKUP_MYSQL_USER     account used for the dump (default root)
  UNIPO_BACKUP_PASSWORD_FILE  mode 600/400 file holding that account's password; without it the
                              password of root or the image's MYSQL_USER is read inside the container
EOF
}

if [ "$#" -gt 0 ]; then
  case "$1" in
    -h | --help) usage; exit 0 ;;
    *) usage >&2; ops_fail 'This script takes no arguments; configure it through the environment.' ;;
  esac
fi

backup_dir="${UNIPO_BACKUP_DIR:-}"
database="${UNIPO_BACKUP_DATABASE:-}"
container="${UNIPO_BACKUP_CONTAINER:-unipo-mysql}"
retention="${UNIPO_BACKUP_RETENTION:-14}"
[ -n "$backup_dir" ] || ops_fail 'UNIPO_BACKUP_DIR is required.'
[ -n "$database" ] || ops_fail 'UNIPO_BACKUP_DATABASE is required.'
case "$backup_dir" in
  /*) ;;
  *) ops_fail 'UNIPO_BACKUP_DIR must be an absolute path.' ;;
esac
ops_is_identifier "$database" || ops_fail 'UNIPO_BACKUP_DATABASE must be a plain identifier.'
ops_is_container_name "$container" || ops_fail 'UNIPO_BACKUP_CONTAINER is not a valid container name.'
[[ "$retention" =~ ^[1-9][0-9]{0,3}$ ]] || ops_fail 'UNIPO_BACKUP_RETENTION must be a positive integer.'

ops_require_tools docker gzip awk find
ops_docker_init
ops_require_running_container "$container"
ops_credentials_init "${UNIPO_BACKUP_MYSQL_USER:-root}" "${UNIPO_BACKUP_PASSWORD_FILE:-}"

mkdir -p -- "$backup_dir"
chmod 700 "$backup_dir"

lock_dir=''
partial_dump=''
partial_sum=''
cleanup() {
  local result=$?
  trap - EXIT
  [ -z "$partial_dump" ] || rm -f -- "$partial_dump"
  [ -z "$partial_sum" ] || rm -f -- "$partial_sum"
  [ -z "$lock_dir" ] || rmdir -- "$lock_dir" 2>/dev/null || true
  unset UNIPO_OPS_MYSQL_PWD
  if [ "$result" != 0 ]; then
    printf '%s Backup failed; no new backup file was kept.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >&2
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# One run per database at a time; flock releases itself if the process dies, the mkdir fallback is for macOS.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$backup_dir/.$database.lock"
  flock -n 9 || ops_fail "Another backup of $database is running."
else
  mkdir -- "$backup_dir/.$database.lockdir" 2>/dev/null \
    || ops_fail "Another backup of $database is running (or $backup_dir/.$database.lockdir is stale)."
  lock_dir="$backup_dir/.$database.lockdir"
fi
# Holding the lock means any partial file of this database belongs to a run that died.
find "$backup_dir" -maxdepth 1 -type f -name ".$database-*.partial" -exec rm -f -- {} +

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="$database-$stamp.sql.gz"
final_dump="$backup_dir/$name"
if [ -e "$final_dump" ] || [ -e "$final_dump.sha256" ]; then
  ops_fail "Backup $name already exists; refusing to overwrite."
fi
partial_dump="$backup_dir/.$name.partial"
partial_sum="$backup_dir/.$name.sha256.partial"

ops_log "Dumping $database from $container as $ops_mysql_user."
started="$(date +%s)"
# Without --databases the dump has no CREATE DATABASE/USE, so a restore lands only in the schema chosen there.
# shellcheck disable=SC2016
"${ops_docker[@]}" exec "${ops_exec_env[@]}" "$container" sh -c "$ops_container_auth"'
  exec mysqldump --protocol=socket --user="$UNIPO_OPS_MYSQL_USER" --default-character-set=utf8mb4 \
    --single-transaction --quick --routines --triggers --events --hex-blob --no-tablespaces \
    --set-gtid-purged=OFF "$1"
' sh "$database" | gzip -c >"$partial_dump"

scan="$(ops_scan_dump "$partial_dump")" || ops_fail 'The new dump is not a readable gzip stream.'
case "$scan" in
  '0 complete') ;;
  *) ops_fail "The new dump failed validation ($scan)." ;;
esac
digest="$(ops_sha256 "$partial_dump")"
printf '%s  %s\n' "$digest" "$name" >"$partial_sum"
chmod 600 "$partial_dump" "$partial_sum"
mv -- "$partial_dump" "$final_dump"
partial_dump=''
mv -- "$partial_sum" "$final_dump.sha256"
partial_sum=''
size="$(wc -c <"$final_dump" | tr -d ' ')"
ops_log "Backup complete: $final_dump ($size bytes, $(( $(date +%s) - started ))s, sha256 $digest)."

# Count-based: if backups stop for a while, the last good copies are never aged out. UTC stamps sort by name.
copies=()
shopt -s nullglob
for existing in "$backup_dir/$database"-*.sql.gz; do
  if [[ "${existing##*/}" =~ ^${database}-[0-9]{8}T[0-9]{6}Z\.sql\.gz$ ]] && [ -f "$existing" ]; then
    copies+=("$existing")
  fi
done
shopt -u nullglob
excess=$((${#copies[@]} - retention))
index=0
while [ "$index" -lt "$excess" ]; do
  rm -f -- "${copies[$index]}" "${copies[$index]}.sha256"
  ops_log "Pruned ${copies[$index]##*/} (retention $retention)."
  index=$((index + 1))
done
