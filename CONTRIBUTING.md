# Contributing to Hadaq

## Workflow (hackathon speed, but safe)

1. Pull first: `git pull --rebase origin main`
2. Branch per task: `<name>/<topic>`, for example `basma/moisture-tuning`, `saleh/brick-node`, `turki/mqtt-bridge`
3. Small commits with clear messages (below). Run `make test` before pushing.
4. Open a pull request to `main`. Omar reviews merges that touch `contracts/` or more than one slice; anything inside your own folder can be merged by you once CI is green.
5. Never force-push to `main`.
6. Before you finish a session, update `HANDOVER.md` (current state, next steps, one session log entry).

## Commit messages

`<type>(<scope>): <what changed>` in English, present tense.

| type | use for |
| --- | --- |
| `feat` | new behavior |
| `fix` | bug fix |
| `docs` | docs only |
| `refactor` | no behavior change |
| `chore` | tooling, deps, config |
| `test` | tests only |

Scopes: `agents`, `sim`, `vision`, `devices`, `api`, `web`, `contracts`, `docs`, `infra`.

Example: `feat(sim): add ponding at the wall foot`

## Rule: no AI tool as a contributor

Use AI tools for help if you like, but **every commit is authored and credited by a team member only**:

- Your git identity must be yours: `git config user.name "Your Name"` and `git config user.email "you@example.com"` (or your GitHub noreply email).
- No `Co-Authored-By:` or `Signed-off-by:` lines naming Claude, ChatGPT, Copilot, Gemini, Cursor or any other AI.
- No "Generated with ..." lines in commit messages or pull request descriptions.
- Turn off co-author attribution in your AI tool's settings before letting it commit.

Enforced twice: `make hooks` installs a commit-msg hook that rejects such commits locally, and the `no-ai-contributors` CI job fails any push or PR that credits an AI tool.

If one slips through before you push: `git commit --amend` and delete the line. If it is already pushed, tell Omar before rewriting history.

## Contracts

`contracts/` is the shared language between the five slices. Change it only in a PR that updates `contracts/README.md`, `packages/hadaq_schemas` and `apps/web/src/types/contracts.ts` together, and post in the group chat.

## Honesty in data

Anything simulated is `synthetic`, anything guessed is `assumed`. The UI shows a "Simulated" badge. Never present a simulated reading, an edited photo or an uncalibrated model as measured fact, in the app, the deck or the video.
