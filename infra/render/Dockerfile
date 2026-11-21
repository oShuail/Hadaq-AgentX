# Hosted demo backend (Render): API + agents + sensor simulator + camera simulator + mock vision in one container.
# Build context is the repo root (see render.yaml). The web app stays on Vercel and points here with VITE_API_URL.
FROM python:3.12-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 \
    MQTT_ENABLED=false HADAQ_LLM=mock DATABASE_URL=sqlite:///./data/hadaq.db PHOTO_DIR=./data/photos

COPY . .
RUN pip install -e packages/hadaq_schemas -e services/simulation -e services/api -e services/agents -e services/vision

CMD ["sh", "infra/render/start.sh"]
