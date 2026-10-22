#!/usr/bin/env bash
# Use the existing ownership-checked two-instance fixture for DP15 acceptance.
set -euo pipefail
if [ "$#" != 0 ]; then
  printf 'Usage: bash scripts/integration/message-http.sh\n' >&2
  exit 2
fi
export UNIPO_DP15_HTTP_INTEGRATION=1
exec bash "$(dirname -- "$0")/identity-e2e.sh"
