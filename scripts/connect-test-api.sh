#!/usr/bin/env bash
set -euo pipefail

# Keep this terminal open. The development API is bound to server loopback only.
# SSH asks for the existing login credential; this script never stores it.
ssh -N \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=20 \
  -o ServerAliveCountMax=3 \
  -L 127.0.0.1:18081:127.0.0.1:18081 \
  ubuntu@49.234.21.63
