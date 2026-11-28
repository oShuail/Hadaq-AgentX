"""AI worker: the core API calls POST /ai/process; we answer 202, then post CaptureResults to callback_url.

It adds its routes to the agents app, so one process on :8100 serves /agents/* and /ai/*.

make ai   (uvicorn hadaq_ai.server:app --port 8100)
Env: HADAQ_DETECTOR=mock|rfdetr, HADAQ_CHECKPOINT=data/models/turaif_v1/checkpoint_best_total.pth

The mock detector draws its boxes for a generated wall, so on real photos its boxes are meaningless:
it only tests the plumbing, and its findings are labeled synthetic.
"""
from __future__ import annotations

import logging
import os
import tempfile
from pathlib import Path

import cv2
import httpx
import numpy as np
from fastapi import BackgroundTasks

from hadaq_agents.server import app
from hadaq_api.privacy import protect
from hadaq_schemas import CaptureResults
from hadaq_schemas.inspection import ProcessRequest
from hadaq_vision.detect import load_detector
from hadaq_vision.findings import label_change, to_findings
from hadaq_vision.rectify import rectify

log = logging.getLogger("hadaq_ai")

BACKEND = os.getenv("HADAQ_DETECTOR", "mock")
CHECKPOINT = os.getenv("HADAQ_CHECKPOINT")
# the real model loads once; the mock is built per photo so its boxes fit the orthophoto
DETECTOR = load_detector(BACKEND, CHECKPOINT) if BACKEND == "rfdetr" else None
DETECTOR_NAME = DETECTOR.name if DETECTOR else "mock-detector"
SYNTHETIC = DETECTOR is None


@app.get("/ai/health")
def ai_health() -> dict:
    return {"status": "ok", "detector": DETECTOR_NAME, "synthetic": SYNTHETIC}


@app.post("/ai/process", status_code=202)
def process(req: ProcessRequest, background: BackgroundTasks) -> dict:
    background.add_task(run, req)
    return {"accepted": True}


def run(req: ProcessRequest) -> None:
    try:
        results, rectified_jpg = analyse(req)
    except Exception as exc:  # tell the core API instead of failing silently
        log.exception("capture %s failed", req.capture_id)
        results, rectified_jpg = CaptureResults(capture_id=req.capture_id, ok=False, error=str(exc),
                                                detector=DETECTOR_NAME), None
    files = {"results": (None, results.model_dump_json(), "application/json")}
    if rectified_jpg is not None:
        files["rectified"] = ("rectified.jpg", rectified_jpg, "image/jpeg")
    try:
        httpx.post(req.callback_url, files=files, timeout=60).raise_for_status()
    except httpx.HTTPError:
        log.exception("callback for %s failed", req.capture_id)


def analyse(req: ProcessRequest) -> tuple[CaptureResults, bytes]:
    response = httpx.get(req.file_url, timeout=60)
    response.raise_for_status()

    # 1. Hide faces again on the server (the phone may not have done it).
    privacy = protect(response.content, ".jpg")
    image = cv2.imdecode(np.frombuffer(privacy.data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("the photo could not be decoded")

    # 2. Rectify to the wall's real size.
    wall = req.wall.model_dump()
    rect = rectify(image, req.corners_px, wall["width_m"], wall["height_m"])

    # 3. Detect on the orthophoto.
    h, w = rect.image.shape[:2]
    detector = DETECTOR or load_detector("mock", wall_bbox_px=[0, 0, w, h])
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / f"{req.capture_id}.jpg"
        cv2.imwrite(str(path), rect.image)
        raw = detector.detect(path)

    # 4. Findings in wall metres + change against earlier captures of this wall.
    findings = to_findings(raw, rect, wall, req.wall.building_id, req.capture_id, DETECTOR_NAME, SYNTHETIC)
    label_change(findings, req.previous_findings)

    # 5. Agent chain (O2): look-alikes, priority, work orders, verifier, summary.
    work_orders, summary_ar, summary_en = [], None, None
    try:
        from hadaq_agents.chain import inspect_capture
    except ImportError:
        log.warning("hadaq_agents.chain not ready yet: sending findings only")
    else:
        out = inspect_capture(findings, req.wall)
        findings, work_orders, summary_ar, summary_en = out.findings, out.work_orders, out.summary_ar, out.summary_en

    ok, jpg = cv2.imencode(".jpg", rect.image, [cv2.IMWRITE_JPEG_QUALITY, 88])
    return CaptureResults(
        capture_id=req.capture_id, scale=rect.scale, px_per_m=round(rect.px_per_m, 2),
        faces_hidden_server=privacy.faces_hidden, findings=findings, work_orders=work_orders,
        summary_ar=summary_ar, summary_en=summary_en, detector=DETECTOR_NAME,
    ), jpg.tobytes() if ok else None
