"""Write JSON Schemas of the v2 contracts to contracts/v2/schemas/ (for the Java core and the iOS app).

Run: python -m hadaq_schemas.export_schemas
"""

from __future__ import annotations

import json
from pathlib import Path

from hadaq_schemas import inspection as v2

MODELS = [
    v2.Registry, v2.Survey, v2.SurveyRequest, v2.CaptureMeta, v2.Capture, v2.CaptureAccepted, v2.Finding,
    v2.WorkOrderDraft, v2.ProcessRequest, v2.CaptureResults, v2.Inspection, v2.ReviewRequest,
    v2.SitePriorityRow, v2.LiveMessageV2,
]

OUT = Path(__file__).resolve().parents[3] / "contracts" / "v2" / "schemas"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for model in MODELS:
        schema = model.model_json_schema()
        (OUT / f"{model.__name__}.json").write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(MODELS)} schemas to {OUT}")


if __name__ == "__main__":
    main()
