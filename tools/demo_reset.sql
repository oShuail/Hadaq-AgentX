-- Demo reset (T4): empties the v2 demo data, keeps the registry (buildings and walls come from contracts/building_b01.json).
-- Run with the core API stopped: bash tools/demo_reset.sh (or make demo-reset). The first survey comes back by itself
-- at the next start (SurveyService seeds it when surveys is empty).
-- With the core API running, POST /api/v2/demo/reset does the same and reseeds the survey at once.
DELETE FROM work_orders;
DELETE FROM findings;
DELETE FROM captures;
DELETE FROM inspections;
DELETE FROM surveys;  -- reseeded at the next start
DELETE FROM evidence WHERE evidence_id REGEXP '^(srv|cap|fnd|wo|ins)_';
