# Hadaq dev commands (macOS / Linux / WSL). On Windows without make, copy the command after the target name.
PY ?= python3
VENV := .venv
BIN := $(VENV)/bin
LOAD_ENV := set -a; [ -f .env ] && . ./.env; set +a;

.PHONY: setup venv vision-ml web-install hooks up down api agents web sensors camera vision sim test

setup: venv web-install hooks ## everything a new teammate needs
	@[ -f .env ] || cp .env.example .env
	@[ -f apps/web/.env.local ] || cp apps/web/.env.example apps/web/.env.local
	@echo "Done. Next: make up, then make api / make agents / make web in separate terminals."

venv:
	$(PY) -m venv $(VENV)
	$(BIN)/pip install -U pip
	$(BIN)/pip install -e packages/hadaq_schemas -e services/simulation -e services/api -e services/agents -e services/vision pytest

vision-ml: ## heavy: torch + RF-DETR (Abdurhmn, or anyone running the real model)
	$(BIN)/pip install -e "services/vision[rfdetr]"

web-install:
	cd apps/web && npm install

hooks: ## blocks AI co-author trailers in commit messages
	git config core.hooksPath .githooks

up:
	docker compose up -d mqtt

down:
	docker compose down

api: ## Turky, http://localhost:8000/docs
	$(LOAD_ENV) $(BIN)/uvicorn hadaq_api.main:app --reload --port 8000

agents: ## Omar, http://localhost:8100/docs
	$(LOAD_ENV) $(BIN)/uvicorn hadaq_agents.server:app --reload --port 8100

web: ## http://localhost:5173 (phone panel: http://<laptop-ip>:5173/?panel=1)
	cd apps/web && npm run dev

sensors: ## Saleh: sim sensors (drop --include-real once the brick node is live)
	$(LOAD_ENV) $(BIN)/python devices/sensor-sim/sensor_sim.py --include-real

camera: ## Abdurhmn: fake fixed camera
	$(LOAD_ENV) $(BIN)/python -m hadaq_vision.camera_sim --folder assets/camera/B01-CAM01 --interval 10

vision: ## Abdurhmn: worker (switch to --backend rfdetr --checkpoint ... when trained)
	$(LOAD_ENV) $(BIN)/python -m hadaq_vision.worker --backend mock

sim: ## Basma: one scenario from the command line
	$(LOAD_ENV) $(BIN)/python -m hadaq_sim.cli run --scenario storm_replay

test:
	$(BIN)/pytest -q packages/hadaq_schemas/tests services/simulation/tests services/api/tests services/agents/tests services/vision/tests
