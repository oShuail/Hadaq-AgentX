"""The hosted demo's background workers in one Python process (owner: Omar): sensor simulator, camera simulator
and mock vision worker, each in a thread. One process instead of three keeps the container under Render's free
512 MB. Locally, run them as separate commands as before.

    python infra/render/helpers.py http://127.0.0.1:10000
"""

import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "devices" / "sensor-sim"))

import sensor_sim  # noqa: E402
from hadaq_vision import camera_sim, worker  # noqa: E402

api = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:10000"
jobs = [
    (sensor_sim.main, ["--include-real", "--transport", "http", "--api", api]),
    (camera_sim.main, ["--folder", str(ROOT / "assets" / "camera" / "B01-CAM01"), "--interval", "15", "--api", api]),
    (worker.main, ["--backend", "mock", "--api", api]),
]
threads = [threading.Thread(target=fn, args=(argv,), daemon=True, name=fn.__module__) for fn, argv in jobs]
for t in threads:
    t.start()
for t in threads:
    t.join()
