# Instructions for AI coding assistants working in this repo

- **Start every session by reading `HANDOVER.md`, and end every session by updating it** (current state, known issues, next steps, plus one new entry at the top of the session log). Same rule for humans.
- Never add yourself (or any AI tool) as author, co-author or committer. No `Co-Authored-By:` trailers, no "Generated with ..." lines in commits or pull requests. Commit as the human team member's git identity only. See CONTRIBUTING.md.
- `contracts/` is the source of truth for message formats, topics and IDs. Do not change it unless asked; if you do, update `packages/hadaq_schemas` and `apps/web/src/types/contracts.ts` in the same change.
- Never label simulated or assumed data as measured (`provenance` field).
- Python 3.11+, Pydantic v2, FastAPI. Web: React + TypeScript + three.js (Vite). The 3D map reads the baked pack in `apps/web/public/site/` (see `tools/site_data/README.md`).
- Run `make test` (and `npm run build` in `apps/web`) before proposing a commit.
