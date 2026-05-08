#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker first, then rerun ./deploy-ip.sh." >&2
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

COMPOSE_FILES=(-f docker-compose.yml -f docker-compose.ip.yml)

if [[ ! -f .env ]]; then
  cp .env.example .env
  cat >&2 <<'MSG'
Created .env from .env.example.
This script deploys backend and frontend for an IP-only server, without Caddy.
By default the backend connects to MySQL on 115.190.3.204:12306 and the frontend publishes port 80.
Edit .env and fill passwords, JWT, existing MinIO settings, and APP_CORS_ALLOWED_ORIGINS, then rerun:
  ./deploy-ip.sh
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
  [[ "$value" == change_me* || "$value" == please-change* || "$value" == *"your-"* || "$value" == *"your_"* ]]
}

default_datasource_url="jdbc:mysql://115.190.3.204:12306/biecuoguo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"

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
  JWT_SECRET
  BOOTSTRAP_ADMIN_PASSWORD
  APP_CORS_ALLOWED_ORIGINS
  MINIO_ENDPOINT
  MINIO_PUBLIC_ENDPOINT
  MINIO_ACCESS_KEY
  MINIO_SECRET_KEY
  MINIO_BUCKET
)

missing=()
placeholders=()
for key in "${required_vars[@]}"; do
  value="$(env_value "$key")"
  if [[ -z "$value" ]]; then
    missing+=("$key")
  elif is_placeholder "$value"; then
    placeholders+=("$key")
  fi
done

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

datasource_url="$(env_value SPRING_DATASOURCE_URL)"
datasource_url="${datasource_url:-$default_datasource_url}"
read -r mysql_host mysql_port < <(extract_mysql_host_port "$datasource_url")

echo "Checking MySQL connection: ${mysql_host}:${mysql_port} ..."
if ! check_tcp "$mysql_host" "$mysql_port"; then
  cat >&2 <<MSG
Cannot connect to MySQL at ${mysql_host}:${mysql_port}.

Check these on server A:
  1. ./deploy-mysql.sh has been run successfully.
  2. docker ps shows unipo-mysql is running.
  3. docker compose --profile local-db ps mysql shows the container is healthy.
  4. The cloud firewall/security group allows TCP ${mysql_port} from this server.
  5. The server firewall allows TCP ${mysql_port}, for example: ufw allow ${mysql_port}/tcp.

Backend was not started because Flyway requires a working database connection at boot.
MSG
  exit 1
fi

echo "Building UniPO backend and frontend..."
BUILDKIT_PROGRESS="${BUILDKIT_PROGRESS:-plain}" "${COMPOSE[@]}" --env-file .env "${COMPOSE_FILES[@]}" build backend
BUILDKIT_PROGRESS="${BUILDKIT_PROGRESS:-plain}" "${COMPOSE[@]}" --env-file .env "${COMPOSE_FILES[@]}" build frontend

echo "Stopping Caddy if it exists, because IP-only deployment publishes frontend directly on port 80..."
"${COMPOSE[@]}" --env-file .env stop caddy >/dev/null 2>&1 || true
"${COMPOSE[@]}" --env-file .env rm -sf caddy >/dev/null 2>&1 || true

echo "Starting backend and frontend only..."
"${COMPOSE[@]}" --env-file .env "${COMPOSE_FILES[@]}" up -d --no-build --force-recreate backend frontend

echo "Service status:"
"${COMPOSE[@]}" --env-file .env "${COMPOSE_FILES[@]}" ps backend frontend

echo
echo "Deployment finished. Frontend publishes port ${IP_HTTP_PORT:-80} directly. Caddy is not used."
echo "Visit: http://SERVER_IP:${IP_HTTP_PORT:-80}/"
echo "Database: ${datasource_url}"
if [[ -n "${SPRING_DATASOURCE_PASSWORD:-}" ]]; then
  echo "Database password: from MYSQL_PASSWORD because SPRING_DATASOURCE_PASSWORD was not set or was still a placeholder."
fi
echo "MySQL and MinIO are not started or changed by this script."
