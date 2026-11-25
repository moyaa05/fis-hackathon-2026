#!/usr/bin/env bash
# Smoke test de la demo completa (SPEC §9/§10). Solo necesita python3 (viene con Ubuntu).
#   ./scripts/smoke.sh               -> golden path completo + reset final (demo limpia)
#   ./scripts/smoke.sh --tamper      -> además prueba la manipulación del audit y su detección
#   BASE=http://otra-ip:18080 ./scripts/smoke.sh
set -euo pipefail
exec python3 "$(dirname "$0")/smoke.py" "$@"
