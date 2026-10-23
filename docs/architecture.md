# ReconHub architecture

## Components

| Service | Technology | Responsibility |
|---|---|---|
| `web` | React 19, PrimeReact, Vite, Nginx | Console for accountants; Nginx serves the build and proxies `/api` |
| `api` | Spring Boot 3.5, Spring Security (JWT), JPA, MyBatis, Spring AI | REST API, exception workflow, file upload, reports, AI endpoints |
| `batch` | Spring Boot 3.5, Spring Batch 5.2, JMS | All heavy work: file ingestion, matching, fee checks, reports, housekeeping |
| `legacy-oms` | Struts 6, JSP, MyBatis 3.5, Tomcat 9 | The shop's existing order system; owner of the `oms.orders` table |
| `postgres` | PostgreSQL 16 | One database: `oms` schema (orders), ReconHub tables, Spring Batch metadata |
| `artemis` | ActiveMQ Artemis | Commands from api to batch, outgoing e-mails |
| `mailpit` | SMTP test server | Receives reports and partner claims |
| `datagen` | Python | Sample orders, partner files and ground truth, once |

`server/core` is a library shared by `api` and `batch`: entities, repositories, Flyway migrations, the matching
engine, the exception workflow, report SQL and the AI services. Both apps run Flyway at start-up; PostgreSQL's advisory
lock makes that safe when they start together.

## Data flow

1. A partner file arrives in `/data/inbox` (SFTP) or through the upload screen.
2. `FileRegistrar` computes the SHA-256 checksum. A file already loaded is recorded as `DUPLICATE` and moved to
   `/data/rejected`; a new one is moved to `/data/archive/yyyy-MM/` and recorded as `PROCESSING`.
   Uploads are registered by the api, which sends an `INGEST` command over JMS; the batch app picks up files from the
   inbox itself. Files whose command never arrived are swept up by the batch poller after a minute.
3. `fileIngestionJob` reads the file with the source's mapping into `payment_lines`.
4. `matchingJob` runs every new line through the matching tiers, raises unpaid orders and attaches suggestions;
   `feeValidationJob` compares charged fees with the fee rules.
5. People work the exception queue. Every action is a method of `ExceptionWorkflowService`, logged in
   `exception_actions` and protected by the exception's `@Version`.
6. `agingAndReportJob` writes the aging and journal CSVs to `/data/reports` and e-mails them every morning.

## Matching

One line at a time (`MatchingEngine.match`), in this order. A wrong automatic match is worse than none, so every
automatic tier needs an identifier or a unique amount, and the exact amount.

| Tier | Rule | Result |
|---|---|---|
| Duplicate | Same source, reference and amount already loaded from another file | `DUPLICATE` exception |
| Exact | Tracking code (carriers), marketplace order id, or one order code in the transfer description; gross amount equals the order | `EXACT` match, or `AMOUNT_DIFF` exception when the amount differs |
| Many to one | Description names several orders (full codes or 4-digit tails) whose total equals the transfer | `MANY_TO_ONE` match |
| Amount and date | No code at all, and exactly one unpaid order with that amount in the previous 72 hours | `AMOUNT_DATE` match |
| Otherwise | | `UNMATCHED_PAYMENT` exception |

After matching: `FEE_DIFF` when the fee differs from the rule (GHN, GHTK, Shopee, TikTok, MoMo), and `UNPAID_ORDER`
for orders delivered more than 7 days ago (TikTok 10, it pays weekly) without money. When a late payout arrives and
matches, the order's open `UNPAID_ORDER` exception is closed automatically.

`tools/datagen/eval_matching.py` checks the automatic matches against the generator's ground truth. On the default
sample (5,315 lines) precision is 100% and 97.7% of lines are matched without a person; CI fails below 100% precision.

## Exception workflow

| Rule | Where |
|---|---|
| Write-offs up to 50,000 ₫ are approved automatically; above that the exception waits in `WRITE_OFF_PENDING` | `requestWriteOff` |
| Only the chief accountant (`APPROVER`) approves, and never their own request | `decideWriteOff` |
| Every write carries the version the user saw; a stale version gets HTTP 409 | `@Version` on `ReconException` |
| The SLA is 48 hours; it is paused while a claim waits for the partner (`IN_REVIEW`) | report SQL, queue sorting |
| Claim e-mails are sent only after the transaction commits | `JmsOutbox.sendAfterCommit` |

## Batch jobs

| Job | Steps | Parameters | Trigger |
|---|---|---|---|
| `fileIngestionJob` | `validateFileStep` → `parseAndStageStep` (chunk 500, skip up to 10% of rows, at least 5) → `finalizeFileStep` | `fileId` | New file |
| `matchingJob` | `matchLinesStep` (chunk 100) → `detectUnpaidStep` → `aiSuggestStep` | `runDate`, `runAt` | After each load, 01:00 |
| `feeValidationJob` | `validateFeeStep` → `aiSuggestStep` | `runDate`, `runAt` | After matching, 01:20 |
| `agingAndReportJob` | `agingSnapshotStep` → `journalExportStep` → `emailReportStep` | `asOf` | 06:00 |
| `archiveAndPurgeJob` | `archiveFilesStep` → `purgeStep` | `runDate` | Sunday 02:00 |
| `seedOrdersJob` | `loadOrdersStep` (`FlatFileItemReader` → `JdbcBatchItemWriter`) | `file`, `checksum` | Demo start-up |

Restarting launches the same job instance again: Spring Batch skips completed steps and resumes the failed step from
its last commit. Rows skipped by `parseAndStageStep` are kept in `file_errors`, including those of the chunk that was
rolled back when the skip limit was reached. Executions left `STARTED` by a crash are marked `FAILED` at start-up so
they can be restarted. All entry points share one lock, so jobs never overlap.

## Security

- `POST /api/auth/login` checks the bcrypt password and returns an HS256 JWT (10 hours) with the user id and role.
- Roles: `ACCOUNTANT`, `APPROVER` (chief accountant), `ADMIN`. Changing a source's column mapping needs `ADMIN` or
  `APPROVER`; approving write-offs needs `APPROVER`; the assistant gives `ADMIN` no amounts.
- The legacy OMS has its own staff accounts (`oms.staff`), as it did before ReconHub.

## AI

`AiGateway` builds the Spring AI `AnthropicChatModel` only when `ANTHROPIC_API_KEY` is set, with default options that
leave out `temperature`, `top_p` and `top_k`. Every call goes through it: it checks the per-user daily limit, records
tokens and latency in `ai_requests`, and returns empty on an error or a refusal so the caller keeps the rule result.
Names and phone numbers are masked (`Redactor`) before a prompt is built. The model never changes data: suggestions
are shown to a person, and the assistant can only call six read-only tools.
