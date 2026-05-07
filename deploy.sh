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
This deploys Caddy, frontend, and backend by default and connects to your existing remote MySQL and MinIO.
Edit .env and fill SPRING_DATASOURCE_*, JWT_SECRET, BOOTSTRAP_ADMIN_PASSWORD, APP_CORS_ALLOWED_ORIGINS,
and existing MinIO settings, then rerun ./deploy.sh.
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

deploy_local_mysql="$(env_value DEPLOY_LOCAL_MYSQL)"
deploy_local_mysql="${deploy_local_mysql:-false}"

required_vars=(
  SPRING_DATASOURCE_URL
  SPRING_DATASOURCE_USERNAME
  SPRING_DATASOURCE_PASSWORD
  JWT_SECRET
  BOOTSTRAP_ADMIN_PASSWORD
  APP_CORS_ALLOWED_ORIGINS
  MINIO_ENDPOINT
  MINIO_PUBLIC_ENDPOINT
  MINIO_ACCESS_KEY
  MINIO_SECRET_KEY
  MINIO_BUCKET
)

case "${deploy_local_mysql,,}" in
  1|true|yes|y|on)
    deploy_local_mysql=true
    required_vars+=(
      MYSQL_PASSWORD
      MYSQL_ROOT_PASSWORD
    )
    ;;
  *)
    deploy_local_mysql=false
    ;;
esac

missing=()
placeholders=()
for key in "${required_vars[@]}"; do
  value="$(env_value "$key")"
  if [[ -z "$value" ]]; then
    missing+=("$key")
  elif [[ "$value" == change_me* || "$value" == please-change* || "$value" == *"your-"* || "$value" == *"your_"* ]]; then
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
services=(backend frontend caddy)
if [[ "$deploy_local_mysql" == true ]]; then
  services=(mysql backend frontend caddy)
fi
"${COMPOSE[@]}" up -d --build "${services[@]}"

echo "Service status:"
"${COMPOSE[@]}" ps

echo
echo "Deployment finished. Caddy publishes ports 80 and 443."
caddy_site_address="$(env_value CADDY_SITE_ADDRESS)"
echo "Caddy site address: ${caddy_site_address:-:80}"
echo "Database: $(env_value SPRING_DATASOURCE_URL)"
if [[ "$deploy_local_mysql" == true ]]; then
  echo "Local MySQL service is enabled by DEPLOY_LOCAL_MYSQL=true."
else
  echo "Local MySQL service is not started. Backend uses the remote datasource above."
fi
