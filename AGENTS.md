# Instructions for AI coding assistants working in this repo

- Never add yourself (or any AI tool) as author, co-author or committer. No `Co-Authored-By:` trailers, no "Generated with ..." lines in commits or pull requests. Commit as the human team member's git identity only. See CONTRIBUTING.md.
- `contracts/` is the source of truth for message formats, topics and IDs. Do not change it unless asked; if you do, update `packages/hadaq_schemas` and `apps/web/src/types/contracts.ts` in the same change.
- Never label simulated or assumed data as measured (`provenance` field).
- Python 3.11+, Pydantic v2, FastAPI. Web: React + TypeScript + CesiumJS (Vite).
- Run `make test` (and `npm run build` in `apps/web`) before proposing a commit.
