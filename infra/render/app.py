"""One web process for the hosted demo (Render): the API with the agents' routes added (owner: Omar).

Render's free plan exposes one port per service, so the agents' /agents/* routes are served by the API process
instead of a second server on :8100. Locally nothing changes: run the API and the agents separately as before.
"""

from hadaq_agents.server import app as agents_app
from hadaq_api.main import app

app.router.routes.extend(route for route in agents_app.routes if getattr(route, "path", "").startswith("/agents"))
