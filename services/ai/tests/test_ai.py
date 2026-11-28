"""The AI worker end to end without the core API: a photo served over HTTP, a fake callback that records the multipart."""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import cv2
import numpy as np
from fastapi.testclient import TestClient

from hadaq_ai.server import app
from hadaq_schemas import CaptureResults

EXAMPLE = Path(__file__).resolve().parents[3] / "contracts" / "v2" / "examples" / "process_request.json"


def serve(photo: bytes, received: list) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(photo)))
            self.end_headers()
            self.wfile.write(photo)

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            received.append((self.headers["Content-Type"], body))
            self.send_response(200)
            self.end_headers()

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def results_part(content_type: str, body: bytes) -> dict:
    boundary = content_type.split("boundary=")[1].encode()
    for part in body.split(b"--" + boundary):
        if b'name="results"' in part:
            return json.loads(part.split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n", 1)[0])
    raise AssertionError("no results part")


def test_photo_comes_back_as_findings_in_wall_metres():
    photo = np.full((2800, 4000, 3), 150, np.uint8)
    ok, jpg = cv2.imencode(".jpg", photo)
    received: list = []
    server = serve(jpg.tobytes(), received)
    base = f"http://127.0.0.1:{server.server_port}"
    req = json.loads(EXAMPLE.read_text(encoding="utf-8"))
    req["file_url"], req["callback_url"] = f"{base}/photo.jpg", f"{base}/results"
    try:
        with TestClient(app) as client:
            assert client.get("/ai/health").json()["detector"] == "mock-detector"
            response = client.post("/ai/process", json=req)
            assert response.status_code == 202
        for _ in range(100):
            if received:
                break
            time.sleep(0.1)
    finally:
        server.shutdown()

    content_type, body = received[0]
    assert b'name="rectified"' in body
    results = CaptureResults.model_validate(results_part(content_type, body))
    assert results.ok and results.scale == "absolute" and results.findings
    wall = req["wall"]
    for f in results.findings:
        u0, v0, u1, v1 = f.box_wall_m
        assert 0 <= u0 < u1 <= wall["width_m"] and 0 <= v0 < v1 <= wall["height_m"]
        assert f.zone_id.startswith("B01-W01-R") and f.provenance == "synthetic" and f.change == "new"
    types = {f.damage_type for f in results.findings}
    assert "crack" in types and "parapet_loss" in types


def test_bad_photo_reports_an_error():
    received: list = []
    server = serve(b"not a jpeg", received)
    base = f"http://127.0.0.1:{server.server_port}"
    req = json.loads(EXAMPLE.read_text(encoding="utf-8"))
    req["file_url"], req["callback_url"] = f"{base}/photo.jpg", f"{base}/results"
    try:
        with TestClient(app) as client:
            assert client.post("/ai/process", json=req).status_code == 202
        for _ in range(100):
            if received:
                break
            time.sleep(0.1)
    finally:
        server.shutdown()
    results = results_part(*received[0])
    assert results["ok"] is False and results["error"]
