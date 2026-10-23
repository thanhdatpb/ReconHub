# ReconHub: notes for Claude Code

Multi-channel payment reconciliation for a Vietnamese online shop. Read `README.md` for the product,
`docs/architecture.md` for how it works, `docs/roadmap.md` for what to build next.

## Layout

| Path | What |
|---|---|
| `server/core` | Shared library: JPA entities, Flyway migrations (`resources/db/migration`), MyBatis report SQL (`resources/mapper`), matching engine, exception workflow, AI services |
| `server/api` | Spring Boot REST API (JWT). Sends work to the batch app over JMS, never runs jobs itself |
| `server/batch` | Spring Batch jobs, inbox poller, schedules, JMS listeners, demo backfill |
| `web` | React 19 + PrimeReact + Vite. `src/api/mock.ts` (sample data) and `src/api/http.ts` (real API) |
| `legacy-oms` | The shop's old order system: Struts 6, JSP, MyBatis, Tomcat 9 (javax). Owns `oms.orders` |
| `tools/datagen` | Sample data generator with ground truth; `eval_matching.py` scores automatic matching |

## Build and verify (the owner's machine has Docker only: no JDK, Maven or Node)

```bash
scripts/verify.sh              # server tests (incl. Testcontainers), web type-check + both builds, legacy OMS build
scripts/verify.sh server       # or: web | oms; add --no-it to skip integration tests
docker compose up -d --build api batch   # rebuild what you changed (web, legacy-oms, datagen likewise)
scripts/verify.sh eval         # matching precision on the running stack; must stay 100%
docker builder prune -a -f     # after every image build: the disks are nearly full
```

- Reset the demo data after changing the schema, the generator or the backfill: `docker compose down -v && docker compose up -d --build`
  (datagen regenerates, the batch app replays 30 days; wait for "Demo history" in `docker compose logs batch`).
- Console http://localhost:8080 (`ha`, `khoa`, `nam`, `bao` / `demo123`), legacy OMS http://localhost:8088/oms/ (`sale` / `sale123`),
  Mailpit http://localhost:8025.
- Do not install or download JDKs, Node or other tools onto the machine or into the repository; use the containers above.

## Conventions

- Language: everything users read (UI, API error messages `{"message": ...}`, e-mails, AI answers) is Vietnamese.
  Code, comments, commit messages and these docs are English.
- Versions are pinned: Spring Boot 3.5.x, Spring AI 1.1.8, Java 21 (`server`), Java 17 + Struts 6 + Tomcat 9 (`legacy-oms`),
  React 19, PrimeReact 10. Do not upgrade Java or major versions as a side effect of a feature.
- Database: PostgreSQL 16. Never edit an existing Flyway migration; add `V<next>__<what>.sql`.
  Reads that join or aggregate go in MyBatis XML; writes go through JPA entities and services.
- Exceptions change only through `ExceptionWorkflowService`: it checks the version (optimistic locking), the role rules
  (maker-checker for write-offs above the threshold) and writes the action history.
- Long work runs in the batch app as Spring Batch jobs. The api registers and sends `Messages.BatchCommand` through
  `JmsOutbox`; the batch app handles it in `BatchCommandListener` and `ReconPipeline`.
- Automatic matching must stay 100% precise (CI runs `eval_matching.py --min-precision 1.0`). When unsure, raise an
  exception for a person instead of matching. If you change the generator, keep its default output stable or update
  the expectations deliberately.
- AI: every model call goes through `AiGateway` (Spring AI, model from config, no `temperature`/`top_p`/`top_k`),
  has a rule-based fallback that works without `ANTHROPIC_API_KEY`, masks customer names and phones with `Redactor`,
  and never changes data without a person confirming. The assistant only gets read-only tools.
- Web: every API function exists in both `src/api/mock.ts` and `src/api/http.ts` (`src/api/check.ts` fails the
  type-check otherwise). Reference data comes from `src/state/meta.ts` (`/api/meta`), never from `src/data/seed.ts` in pages.
- Security: new endpoints are authenticated; restrict by role where the business needs it (`@PreAuthorize`), and add a
  test for the rule in `ApiSecurityIntegrationTest` or a new integration test.

## Done means

- Tests added or updated for new logic; `scripts/verify.sh` passes.
- The stack rebuilds and the feature works in the browser; `scripts/verify.sh eval` still reports 100% precision.
- `README.md` / `docs/*` updated where behaviour changed; the item is ticked in `docs/roadmap.md`.

## Git

- Branch from the latest `main` (`feature/<short-name>`), small commits, push the branch. The owner opens and merges
  the pull request; never push to `main` and never merge.
- CI (`.github/workflows/ci.yml`) runs all tests and an end-to-end compose run on every push; check it after pushing.
