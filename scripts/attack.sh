#!/usr/bin/env bash
# =====================================================================
# Demo de seguridad perimetral (SPEC §8).
# Un "atacante" intenta adivinar firmas a lo bruto contra el endpoint de autorización.
# Se lanza desde un CONTENEDOR aparte (otra IP), así el dashboard del jurado no se ve afectado.
#
# Uso (en el portátil de la demo, con el stack arrancado):   ./scripts/attack.sh
# Requisito: haber hecho antes, con WiFi:                      docker pull curlimages/curl
# =====================================================================
set -euo pipefail

ATTEMPTS="${ATTEMPTS:-25}"
GATEWAY="${GATEWAY_HOST:-fis-gateway}"            # nombre del contenedor de nginx
BASE_HOST="${BASE:-http://localhost:18080}"       # para comprobar el dashboard desde el host

NET="${NET:-$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' "$GATEWAY" | head -n1)}"
if [[ -z "$NET" ]]; then echo "No encuentro la red de $GATEWAY. ¿Está arrancado el stack?"; exit 1; fi

echo "== Ataque: $ATTEMPTS intentos de firma en paralelo contra la autorización (red: $NET) =="
docker run --rm --network "$NET" --entrypoint sh curlimages/curl -c "
for i in \$(seq 1 $ATTEMPTS); do
  curl -s -o /dev/null -w 'attempt %{http_code}\n' -X POST \
       -H 'Content-Type: application/json' -d '{\"signature\":\"AAAA\"}' \
       http://$GATEWAY/api/payments/999/rotation-authorization &
done; wait" | sort | uniq -c | sed 's/attempt/× HTTP/'

echo
echo "== Mientras tanto, el dashboard (otra IP, solo lecturas) =="
ok=0
for i in $(seq 1 10); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_HOST/api/state" || true)
  [[ "$code" == "200" ]] && ok=$((ok+1))
done
echo "GET /api/state: $ok/10 respuestas 200"
echo
echo "Lectura: los 404 pasaron el gateway (el pago 999 no existe: sin efectos); los 429 los cortó nginx."
