# ReconHub

Multi-channel payment reconciliation for online sellers. ReconHub ingests settlement files from
carriers (GHN, GHTK), marketplaces (Shopee, TikTok Shop), banks and e-wallets, matches every money
line against orders from the order management system (OMS), and sends what rules cannot match to an
exception queue where people resolve it with AI suggestions.

## Repository layout

| Path | Status | What it is |
|---|---|---|
| `web/` | **UI prototype, runs on sample data** | React + TypeScript + PrimeReact console |
| `api/` | planned | Spring Boot REST API, Spring Security, JPA + MyBatis, Spring AI |
| `batch/` | planned | Spring Batch jobs, run by cron/systemd timers |
| `legacy-oms/` | planned | Legacy order system (Struts + JSP + MyBatis on Tomcat), the source of orders |
| `tools/datagen/` | planned | Python generator for orders, partner files and ground truth |
| `docs/` | planned | SRS, design, batch design, test plan, runbook |
| `ops/` | planned | Docker Compose, Nginx, systemd units, scripts |

## Running the UI

```bash
cd web
npm install
npm run dev        # http://localhost:5173
npm run build      # type-check + production build in web/dist
```

All data comes from a deterministic generator in `web/src/data/seed.ts` (fixed "today":
22/10/2026 09:30). Use the user picker in the top bar to switch between accountant and chief
accountant and try the write-off approval flow.

## Screens

| Route | Screen | Purpose |
|---|---|---|
| `/` | Overview | What needs attention now, auto-match rate, unreconciled money, today's file arrivals |
| `/exceptions` | Exception queue | Filter, bulk-assign, SLA; overdue rows first |
| `/exceptions/:id` | Exception detail | OMS side vs money side, AI suggestion with reasons, actions, maker-checker, history |
| `/results` | Reconciliation results | Per day and source: auto-matched, resolved, still open; drill into match groups |
| `/aging` | Aging & journal | Partner receivables by age bucket; journal entries (112/131/641/811) as CSV |
| `/files` | File intake | Every received file, checksum de-duplication, error rows, upload |
| `/sources` | Source configuration | Column mapping (with AI suggestion for new formats), fee rules, schedule |
| `/batch` | Batch monitor | Spring Batch executions and steps, restart, AI explanation of failures |
| `/assistant` | Data assistant | Questions in Vietnamese answered through read-only tools |

## API contract the UI expects

`web/src/api/index.ts` is a mock with the same shape as the planned REST API, so replacing it with
`fetch` calls is the only UI change needed.

| Mock function | Planned endpoint |
|---|---|
| `getDashboard` | `GET /api/dashboard` |
| `listExceptions(filter)` | `GET /api/exceptions?tab=&type=&source=&assignee=&overdue=&q=&page=` |
| `getException(id)` | `GET /api/exceptions/{id}` |
| `assignExceptions` | `POST /api/exceptions/assign` |
| `matchException` | `POST /api/exceptions/{id}/match` (body has `version` for optimistic locking) |
| `requestWriteOff` / `decideWriteOff` | `POST /api/exceptions/{id}/write-off`, `POST /api/exceptions/{id}/write-off/decision` |
| `sendClaim` / `markDuplicate` / `addComment` | `POST /api/exceptions/{id}/claim`, `/duplicate`, `/comments` |
| `searchOrders(q)` | `GET /api/orders?q=` |
| `listFiles` / `getFileErrors` / `uploadFile` | `GET /api/files`, `GET /api/files/{id}/errors`, `POST /api/files` (multipart) |
| `listSources` / `suggestMapping` / `saveMapping` | `GET /api/sources`, `POST /api/sources/{code}/mapping/suggest`, `PUT /api/sources/{code}/mapping` |
| `listReconDays` / `listMatchGroups` | `GET /api/recon/days`, `GET /api/recon/days/{date}/{source}/groups` |
| `getAging` / `getJournal` | `GET /api/aging`, `GET /api/journal?month=` |
| `listExecutions` / `restartExecution` / `explainExecution` | `GET /api/batch/executions`, `POST /api/batch/executions/{id}/restart`, `POST /api/ai/batch/{id}/explain` |
| `ask` | `POST /api/ai/ask` |
