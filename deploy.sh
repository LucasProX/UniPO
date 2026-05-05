#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker first, then rerun ./deploy.sh." >&2
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
Edit .env and fill MYSQL_*, JWT_SECRET, BOOTSTRAP_ADMIN_PASSWORD, and existing MinIO settings, then rerun ./deploy.sh.
MSG
  exit 1
fi

required_vars=(
  MYSQL_PASSWORD
  MYSQL_ROOT_PASSWORD
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
  line="$(grep -E "^${key}=" .env | tail -n 1 || true)"
  value="${line#*=}"
  if [[ -z "$line" || -z "$value" ]]; then
    missing+=("$key")
  elif [[ "$value" == change_me* || "$value" == *"your-"* || "$value" == *"your_"* ]]; then
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

echo "Building and starting UniPO services..."
"${COMPOSE[@]}" up -d --build mysql backend frontend

echo "Service status:"
"${COMPOSE[@]}" ps

echo
app_port="$(grep -E '^APP_PORT=' .env | tail -n 1 | cut -d= -f2-)"
echo "Deployment finished. Web port: ${app_port:-80} (override APP_PORT in .env)."
