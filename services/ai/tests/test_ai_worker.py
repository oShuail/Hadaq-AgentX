"""The AI worker on a generated wall photo, without Spring: download, rectify, detect (mock), findings."""
import json
from pathlib import Path

import cv2
import numpy as np
from fastapi.testclient import TestClient

from hadaq_ai import server
from hadaq_schemas.inspection import ProcessRequest

EXAMPLE = Path(__file__).resolve().parents[3] / "contracts" / "v2" / "examples" / "process_request.json"


class _Response:
    def __init__(self, content: bytes) -> None:
        self.content = content


def _photo() -> bytes:
    ok, jpg = cv2.imencode(".jpg", np.full((3000, 4000, 3), 170, np.uint8))
    return jpg.tobytes()


def test_analyse_returns_findings_in_wall_metres(monkeypatch):
    monkeypatch.setattr(server.httpx, "get", lambda url, timeout: _Response(_photo()))
    req = ProcessRequest.model_validate(json.loads(EXAMPLE.read_text()))
    results, jpg = server.analyse(req)
    assert results.ok and results.scale == "absolute" and results.findings
    for f in results.findings:
        assert f.zone_id.startswith("B01-W01-R")
        assert 0 <= f.box_wall_m[0] <= req.wall.width_m and 0 <= f.box_wall_m[1] <= req.wall.height_m
        assert f.provenance == "synthetic"  # the mock detector never passes as a real model
    assert jpg[:2] == b"\xff\xd8"  # the rectified image is a JPEG


def test_process_answers_202_and_reports_a_failure_to_spring(monkeypatch):
    posted = {}
    monkeypatch.setattr(server.httpx, "get", lambda url, timeout: _Response(b"not an image"))
    monkeypatch.setattr(server.httpx, "post", lambda url, files, timeout: posted.update(url=url, files=files) or _Ok())
    body = json.loads(EXAMPLE.read_text())
    res = TestClient(server.app).post("/ai/process", json=body)
    assert res.status_code == 202
    sent = json.loads(posted["files"]["results"][1])
    assert posted["url"] == body["callback_url"] and sent["ok"] is False and sent["error"]


class _Ok:
    def raise_for_status(self) -> None:
        return None