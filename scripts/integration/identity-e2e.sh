#!/usr/bin/env bash
# Python owns the local child processes; the independent Compose wrappers own their fixtures.
set -euo pipefail
exec python3 "$(dirname -- "$0")/identity-e2e.py" "$@"
