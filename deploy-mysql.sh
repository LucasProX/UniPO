#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker first, then rerun ./deploy-mysql.sh." >&2
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
This script deploys only MySQL for server A. Edit MYSQL_PASSWORD and MYSQL_ROOT_PASSWORD, then rerun:
  ./deploy-mysql.sh
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

required_vars=(
  MYSQL_PASSWORD
  MYSQL_ROOT_PASSWORD
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

if (( ${#missing[@]} )); then
  echo "Missing required values in .env: ${missing[*]}" >&2
  exit 1
fi

if (( ${#placeholders[@]} )); then
  echo "Replace placeholder values in .env: ${placeholders[*]}" >&2
  exit 1
fi

echo "Starting UniPO MySQL only..."
"${COMPOSE[@]}" --env-file .env --profile local-db up -d mysql

echo "MySQL status:"
"${COMPOSE[@]}" --env-file .env ps mysql

echo
echo "MySQL is published on $(env_value MYSQL_PUBLIC_BIND || true):$(env_value MYSQL_PUBLIC_PORT || true), default 0.0.0.0:12306."
echo "Database: $(env_value MYSQL_DATABASE || true)"
echo "User: $(env_value MYSQL_USER || true)"
