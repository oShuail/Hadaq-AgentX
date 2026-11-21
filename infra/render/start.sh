#!/bin/sh
# Starts the hosted demo in one container (Render): the API with the agents on $PORT, plus the sensor simulator,
# the camera simulator and the mock vision worker (one background process, infra/render/helpers.py) posting to it
# over HTTP.
set -e
PORT="${PORT:-10000}"
export API_URL="http://127.0.0.1:${PORT}"
export AGENTS_URL="http://127.0.0.1:${PORT}"
export API_PUBLIC_URL="${RENDER_EXTERNAL_URL:-http://localhost:${PORT}}"
mkdir -p data/photos

(
  # Wait for the API, then start the simulators and the vision worker.
  until python -c "import urllib.request; urllib.request.urlopen('${API_URL}/health', timeout=2)" 2>/dev/null; do sleep 1; done
  python infra/render/helpers.py "${API_URL}" &
  # A first storm run so the risk layer has scores as soon as the page opens.
  python -c "import json, urllib.request; urllib.request.urlopen(urllib.request.Request('${API_URL}/simulations', data=json.dumps({'building_id': 'B01', 'wall_id': 'W01', 'scenario': 'storm_replay', 'weather': {'mode': 'synthetic_storm'}, 'requested_by': 'user'}).encode(), headers={'content-type': 'application/json'}))" || true
  wait
) &

exec uvicorn --app-dir infra/render app:app --host 0.0.0.0 --port "${PORT}"
