#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required." >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "Docker Compose is required." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example. Edit passwords, then rerun ./reset-data.sh." >&2
  exit 1
fi

echo "Stopping UniPO data stack and deleting MySQL/MinIO volumes..."
"${COMPOSE[@]}" --env-file .env -f docker-compose.yml down -v --remove-orphans

echo "Starting fresh MySQL and MinIO..."
"${COMPOSE[@]}" --env-file .env -f docker-compose.yml up -d

echo "Current status:"
"${COMPOSE[@]}" --env-file .env -f docker-compose.yml ps
