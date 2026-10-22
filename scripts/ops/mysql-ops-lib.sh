# shellcheck shell=bash
# Shared helpers for mysql-backup.sh and mysql-restore.sh. Sourced, never executed directly.
# Passwords never reach argv: they stay in the container env, or travel as an env var named on docker exec.

ops_log() {
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

ops_fail() {
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
  exit 2
}

ops_require_tools() {
  local ops_tool
  for ops_tool in "$@"; do
    command -v "$ops_tool" >/dev/null 2>&1 || ops_fail "Missing required tool: $ops_tool"
  done
  if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    ops_fail 'Missing required tool: sha256sum (or shasum)'
  fi
}

# Schema and account names are interpolated into SQL, so only plain identifiers are accepted.
ops_is_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9_]{1,64}$ ]]
}

ops_is_container_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]]
}

ops_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" | awk '{print $1}'
  else
    shasum -a 256 -- "$1" | awk '{print $1}'
  fi
}

ops_file_mode() {
  stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"
}

ops_file_owner() {
  stat -c '%u' -- "$1" 2>/dev/null || stat -f '%u' -- "$1"
}

# Pins the current Docker context and refuses remote daemons, so a stray DOCKER_HOST never dumps or restores elsewhere.
ops_docker_init() {
  local ops_context ops_endpoint
  ops_context="$(docker context show)"
  ops_endpoint="$(docker context inspect "$ops_context" --format '{{.Endpoints.docker.Host}}')"
  case "$ops_endpoint" in
    unix://*) ;;
    *) ops_fail 'Refusing a non-local Docker context.' ;;
  esac
  ops_docker=(docker --context "$ops_context")
}

ops_require_running_container() {
  local ops_running
  ops_running="$("${ops_docker[@]}" container inspect "$1" --format '{{.State.Running}}' 2>/dev/null || true)"
  [ "$ops_running" = true ] || ops_fail "Container $1 is not running."
}

# Selects where the MySQL password comes from. With a password file it is read here and forwarded by name only;
# otherwise root or the image's MYSQL_USER use the password already in the container env.
ops_credentials_init() {
  ops_mysql_user="$1"
  local ops_password_file="$2"
  ops_is_identifier "$ops_mysql_user" || ops_fail 'MySQL account must be a plain identifier.'
  ops_exec_env=(-e "UNIPO_OPS_MYSQL_USER=$ops_mysql_user")
  if [ -z "$ops_password_file" ]; then
    return
  fi
  if [ ! -f "$ops_password_file" ] || [ -L "$ops_password_file" ]; then
    ops_fail 'Password file must be a regular file.'
  fi
  [ "$(ops_file_owner "$ops_password_file")" = "$(id -u)" ] || ops_fail 'Password file must be owned by the current user.'
  case "$(ops_file_mode "$ops_password_file")" in
    400 | 600) ;;
    *) ops_fail 'Password file must have mode 600 or 400.' ;;
  esac
  UNIPO_OPS_MYSQL_PWD=''
  IFS= read -r UNIPO_OPS_MYSQL_PWD <"$ops_password_file" || true
  [ -n "$UNIPO_OPS_MYSQL_PWD" ] || ops_fail 'Password file is empty.'
  export UNIPO_OPS_MYSQL_PWD
  ops_exec_env+=(-e UNIPO_OPS_MYSQL_PWD)
}

# Prepended to every in-container command. It only references variables, so the script text carries no secret.
# shellcheck disable=SC2016
ops_container_auth='
if [ -n "${UNIPO_OPS_MYSQL_PWD:-}" ]; then
  MYSQL_PWD="$UNIPO_OPS_MYSQL_PWD"
elif [ "$UNIPO_OPS_MYSQL_USER" = root ] && [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
elif [ "$UNIPO_OPS_MYSQL_USER" = root ] && [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ]; then
  MYSQL_PWD="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
elif [ "$UNIPO_OPS_MYSQL_USER" = "${MYSQL_USER:-}" ] && [ -n "${MYSQL_PASSWORD:-}" ]; then
  MYSQL_PWD="$MYSQL_PASSWORD"
else
  echo "No password source for this MySQL account; pass a protected password file." >&2
  exit 2
fi
unset UNIPO_OPS_MYSQL_PWD
export MYSQL_PWD
'

# Runs one SQL statement through the local socket and prints tab-separated rows without headers.
ops_sql() {
  local ops_container="$1" ops_query="$2"
  # shellcheck disable=SC2016
  "${ops_docker[@]}" exec "${ops_exec_env[@]}" "$ops_container" sh -c "$ops_container_auth"'
    exec mysql --protocol=socket --user="$UNIPO_OPS_MYSQL_USER" --default-character-set=utf8mb4 \
      --batch --skip-column-names --execute="$1"
  ' sh "$ops_query"
}

# One pass over the decompressed dump: counts statements that would switch or drop a database, and checks the
# trailer that mysqldump writes only after a complete run. Prints "<switch-count> <complete|truncated>".
ops_scan_dump() {
  gzip -dc -- "$1" | awk '
    /^(USE|CREATE DATABASE|DROP DATABASE)[ \t`]/ { switches++ }
    { last = $0 }
    END { printf "%d %s\n", switches + 0, (last ~ /^-- Dump completed/) ? "complete" : "truncated" }
  '
}
