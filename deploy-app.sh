#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker first, then rerun ./deploy-app.sh." >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "Docker Compose is required. Install the compose plugin or docker-compose." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  cat >&2 <<'MSG'
Created .env from .env.example.
This script deploys only backend, frontend, and Caddy.
There is no default database host: set SPRING_DATASOURCE_URL in .env explicitly.
Edit .env and fill the database URL, passwords, CORS, JWT, SMTP, and MinIO settings, then rerun:
  ./deploy-app.sh
MSG
  exit 1
fi

env_value() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" .env | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    printf ''
  else
    printf '%s' "${line#*=}"
  fi
}

is_placeholder() {
  local value="$1"
  local normalized
  normalized="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  [[ "$normalized" == change_me* || "$normalized" == change-this* || "$normalized" == please-change* \
    || "$normalized" == *"your-"* || "$normalized" == *"your_"* || "$normalized" == local-dev-only* ]]
}

extract_mysql_host_port() {
  local url="$1"
  local without_prefix="${url#jdbc:mysql://}"
  local authority="${without_prefix%%/*}"
  local host="${authority%%:*}"
  local port="${authority##*:}"
  if [[ "$authority" == "$host" ]]; then
    port="3306"
  fi
  printf '%s %s\n' "$host" "$port"
}

check_tcp() {
  local host="$1"
  local port="$2"
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 5 "$host" "$port"
  else
    timeout 5 bash -c "cat < /dev/null > /dev/tcp/$host/$port"
  fi
}

required_vars=(
  # 数据库地址必须显式给。以前这里有个写死远端 IP 的默认值，
  # 那台服务器已经停用 —— 跟着默认值跑只会连到一个不该碰的库。
  SPRING_DATASOURCE_URL
  JWT_SECRET
  BOOTSTRAP_ADMIN_PASSWORD
  # 验证码真实模式必需。没配的话后端会直接拒绝启动（config/StartupSafetyGuard.java），
  # 因为没有 SMTP 就等于注册和找回密码整条链路是断的。
  SMTP_HOST
  SMTP_USERNAME
  SMTP_PASSWORD
  APP_CORS_ALLOWED_ORIGINS
  MINIO_ENDPOINT
  MINIO_PUBLIC_ENDPOINT
  MINIO_ACCESS_KEY
  MINIO_SECRET_KEY
  MINIO_BUCKET
)

missing=()
placeholders=()
invalid=()
for key in "${required_vars[@]}"; do
  value="$(env_value "$key")"
  if [[ -z "$value" ]]; then
    missing+=("$key")
  elif is_placeholder "$value"; then
    placeholders+=("$key")
  fi
done

bootstrap_admin_password="$(env_value BOOTSTRAP_ADMIN_PASSWORD)"
if [[ -n "$bootstrap_admin_password" ]]; then
  bootstrap_admin_password_bytes="$(LC_ALL=C printf '%s' "$bootstrap_admin_password" | wc -c | tr -d '[:space:]')"
  if (( bootstrap_admin_password_bytes < 32 || bootstrap_admin_password_bytes > 72 )); then
    invalid+=("BOOTSTRAP_ADMIN_PASSWORD (must be 32-72 UTF-8 bytes)")
  fi
fi

db_password="$(env_value SPRING_DATASOURCE_PASSWORD)"
mysql_password="$(env_value MYSQL_PASSWORD)"
if [[ -z "$db_password" && -z "$mysql_password" ]]; then
  missing+=("SPRING_DATASOURCE_PASSWORD or MYSQL_PASSWORD")
elif [[ -n "$db_password" && -n "$mysql_password" ]] && is_placeholder "$db_password" && ! is_placeholder "$mysql_password"; then
  export SPRING_DATASOURCE_PASSWORD="$mysql_password"
elif { [[ -n "$db_password" ]] && is_placeholder "$db_password"; } || { [[ -z "$db_password" && -n "$mysql_password" ]] && is_placeholder "$mysql_password"; }; then
  placeholders+=("SPRING_DATASOURCE_PASSWORD or MYSQL_PASSWORD")
fi

if (( ${#missing[@]} )); then
  echo "Missing required values in .env: ${missing[*]}" >&2
  exit 1
fi

if (( ${#placeholders[@]} )); then
  echo "Replace placeholder values in .env: ${placeholders[*]}" >&2
  exit 1
fi

if (( ${#invalid[@]} )); then
  echo "Invalid values in .env: ${invalid[*]}" >&2
  exit 1
fi

datasource_url="$(env_value SPRING_DATASOURCE_URL)"
read -r mysql_host mysql_port < <(extract_mysql_host_port "$datasource_url")

echo "Checking MySQL connection: ${mysql_host}:${mysql_port} ..."
if ! check_tcp "$mysql_host" "$mysql_port"; then
  cat >&2 <<MSG
Cannot connect to MySQL at ${mysql_host}:${mysql_port}.

Check:
  1. That MySQL instance is running and reachable from here.
  2. SPRING_DATASOURCE_URL in .env really points at it.
  3. The user/password in .env match that instance.
  4. The cloud firewall/security group allows TCP ${mysql_port}.
  5. The server firewall allows TCP ${mysql_port}, for example: ufw allow ${mysql_port}/tcp.

Backend was not started because Flyway requires a working database connection at boot.
MSG
  exit 1
fi

echo "Building UniPO backend and frontend..."
BUILDKIT_PROGRESS="${BUILDKIT_PROGRESS:-plain}" "${COMPOSE[@]}" --env-file .env build backend
BUILDKIT_PROGRESS="${BUILDKIT_PROGRESS:-plain}" "${COMPOSE[@]}" --env-file .env build frontend

echo "Starting backend, frontend, and Caddy only..."
"${COMPOSE[@]}" --env-file .env up -d --no-build backend frontend caddy

echo "Service status:"
"${COMPOSE[@]}" --env-file .env ps backend frontend caddy

echo
echo "Deployment finished. Caddy publishes ports 80 and 443."
echo "Caddy site address: $(env_value CADDY_SITE_ADDRESS || true)"
echo "Database: ${datasource_url}"
if [[ -n "${SPRING_DATASOURCE_PASSWORD:-}" ]]; then
  echo "Database password: from MYSQL_PASSWORD because SPRING_DATASOURCE_PASSWORD was not set or was still a placeholder."
fi
echo "MySQL container is not started by this script."
