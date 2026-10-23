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
   `feeValidationJob` compares charged fees with the fee schedule version in force on the payment date.
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

After matching: `FEE_DIFF` when the fee differs from the fee schedule (GHN, GHTK, Shopee, TikTok, MoMo), and `UNPAID_ORDER`
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

## Fee schedules

Expected fees come from versioned fee schedules (`fee_schedules`, V6), not from code. A version is a list of
components, each rounded half-up to the đồng and then added (`FeeFormula`): a fixed amount or a percentage of the COD,
the order value or the transaction, with an optional minimum, applied always, for remote orders, or above / up to a
threshold. Version 1 of GHN, GHTK, Shopee, TikTok and MoMo reproduces the formulas that used to be hard-coded and
applies "from the start" (2000-01-01); `FeeScheduleSeedIntegrationTest` compares it with those formulas on 20,000+ orders.

| Rule | Where |
|---|---|
| A line's expected fee comes from the approved version with the latest `effective_from` on or before its transaction date (Vietnam time); the higher version wins a tie | `FeeCalculator` |
| Anyone proposes a version with a reason; a chief accountant or admin approves or rejects it, never their own proposal | `FeeScheduleService` |
| Approval numbers the version and sends `REVALIDATE_FEES` after commit | `FeeScheduleService.approve` |
| `feeRevalidationJob` closes an open (`OPEN`, `IN_REVIEW`) `FEE_DIFF` of that source paid on or after the version's date only when the fee charged equals the fee of the version in force on the payment date; it logs `FEE_RULE_UPDATED` "Theo biểu phí phiên bản N…". Pending write-offs and closed exceptions are left alone; matching is not changed | `FeeRevalidation`, `ExceptionWorkflowService.resolveByFeeSchedule` |
| A repeated difference is suggested as a new tariff (`UPDATE_FEE_RULE`) only when at least 5 payments of one source within 14 days were charged exactly the same amount more, and one fixed amount above an order-value threshold explains all of them while every payment at or below it was charged correctly. The suggestion says the partner may also be wrong and to claim when no change was announced. A percentage overcharge stays `CLAIM` | `FeeRuleInference`, `SuggestionService` |
| "Đề xuất cập nhật biểu phí" fills a proposal from that rule (version in force + the inferred component, effective date, evidence) without saving it | `GET /api/exceptions/{id}/fee-proposal` |

## VietQR on orders

A bank-transfer order (`pay_via = VCB`) carries a VietQR code with the order value and the order code as the transfer
description. The customer scans it, so the statement line has the exact amount and the code, and matches `EXACT`
instead of waiting for `AMOUNT_DATE` or a person (roadmap 2.1). The code is built in the system; no outside service is
called.

The payload is EMVCo merchant-presented mode: fields of tag, two-digit length, value.

| Tag | Value |
|---|---|
| `00` | `01`, payload format |
| `01` | `12` dynamic (with amount); `11` static |
| `38` | Beneficiary: `00` NAPAS GUID `A000000727`; `01` = `00` bank BIN (6 digits) + `01` account number; `02` service `QRIBFTTA` (transfer to an account) |
| `53` | `704`, VND |
| `54` | Amount in đồng, digits only |
| `58` | `VN` |
| `62` | `08`: transfer description, here the order code. Limited to 25 letters, digits or spaces (a conservative choice: banks differ, and diacritics are refused) |
| `63` | CRC-16/CCITT-FALSE (polynomial 0x1021, initial 0xFFFF) over everything before it, including `6304`, as 4 hex digits |

No merchant name or city (tags 59/60): VietQR transfer codes leave them out, and the banking app shows the account
holder that the bank returns.

| Rule | Where |
|---|---|
| Payload, CRC and PNG | `VietQr` (`server/core` `vn.reconhub.core.vietqr`; the same class in `legacy-oms` `vn.shop.oms.vietqr`, which does not depend on ReconHub) |
| The two copies may differ only in the package line, and share `vietqr/vectors.csv` | `VietQrTest` in both modules (when the whole repository is checked out), `scripts/check-vietqr-copies.sh` in CI and `verify.sh` |
| Each image is read back with ZXing's detector; if a symbol fools it (about one order code in ten at level M), the next error-correction level (Q, L, H) is used | `VietQr.png` |
| Account from `VIETQR_BIN`, `VIETQR_ACCOUNT`, `VIETQR_ACCOUNT_NAME`; sample values by default | `reconhub.vietqr.*`, `QrAccount` (OMS) |
| Only VCB orders have a code; accountants and the chief accountant fetch it, the admin gets 403 | `OrderQrService`, `OrderQrController` |

Sources the format was checked against:

- EMVCo, [*EMV QR Code Specification for Payment Systems: Merchant-Presented Mode*](https://www.emvco.com/emv-technologies/qr-codes/)
  (TLV layout, tags 00, 01, 53, 54, 58, 62, 63, CRC).
- NAPAS, [QR code payment service](https://en.napas.com.vn/qr-code-payment-service-184230612215937838.htm): VietQR is
  merchant-presented and follows the State Bank's EMVCo-based standard. NAPAS's field-level specification (cited by
  libraries as "QR Switching" v1.5.2) is not published openly and was not used.
- Tag 38 layout and three worked payloads (the first three rows of `vectors.csv`):
  [Tự viết bộ tạo và đọc mã VietQR bằng JavaScript: TLV, CRC16 và 6 lỗi hay gặp](https://viblo.asia/p/tu-viet-bo-tao-va-doc-ma-vietqr-bang-javascript-tlv-crc16-va-6-loi-hay-gap-3RL1BowpVao) (viblo.asia).
  The other rows were computed with an independent Python implementation.
- CRC-16/CCITT-FALSE check value `29B1` for "123456789": [CRC catalogue](https://reveng.sourceforge.io/crc-catalogue/16.htm).

The final check is a scan with a real banking app ([runbook](runbook.md#scan-an-orders-vietqr-code-with-a-banking-app)).

### Effect on matching (simulated)

The sample data has no real VietQR payments. The generator's `--qr-rate` (`DATAGEN_QR_RATE`) **assumes** that share
of Vietcombank customers pay by scanning the code; the numbers below are a simulation with those assumed rates, not
measurements from real customers. Measured on 23/10/2026: `docker compose down -v`, start with the rate, wait for the
backfill, run `eval_matching.py`. Same seed (20261022) and date at every rate; rate 0 is the default data CI uses.

| Customers paying by VietQR (assumed) | Lines | Automatic matches | Precision | Automation rate, all sources | Automation rate, Vietcombank |
|---|---|---|---|---|---|
| 0 (today) | 5,315 | 5,186 | 100% | 97.66% | 92.50% of 733 |
| 0.3 | 5,319 | 5,207 | 100% | 97.99% | 94.98% of 737 |
| 0.6 | 5,330 | 5,236 | 100% | 98.33% | 97.32% of 745 |
| 0.9 | 5,339 | 5,255 | 100% | 98.52% | 98.81% of 756 |

A QR payer's line has the exact amount and the code, so it is matched `EXACT`: 681 of 687 at 0.9. The other 6 got a
`DUPLICATE` exception, as plain lines with the code do at rate 0 (6 of 4,928). What the rate removes are the customers
who forget the code, mistype it, pay two orders at once or pay short. The Vietcombank line count moves a little because
a QR payer pays each order separately; MoMo moves by one to three lines because the bank lines then draw random numbers
in a different order. Vietcombank's 89.5% on the Overview is the last 7 days; the table covers all 30 days.

## Batch jobs

| Job | Steps | Parameters | Trigger |
|---|---|---|---|
| `fileIngestionJob` | `validateFileStep` → `parseAndStageStep` (chunk 500, skip up to 10% of rows, at least 5) → `finalizeFileStep` | `fileId` | New file |
| `matchingJob` | `matchLinesStep` (chunk 100) → `detectUnpaidStep` → `aiSuggestStep` | `runDate`, `runAt` | After each load, 01:00 |
| `feeValidationJob` | `validateFeeStep` → `aiSuggestStep` | `runDate`, `runAt` | After matching, 01:20 |
| `feeRevalidationJob` | `revalidateFeeStep` | `scheduleId` | A fee schedule version is approved (`REVALIDATE_FEES` over JMS) |
| `agingAndReportJob` | `agingSnapshotStep` → `journalExportStep` → `emailReportStep` | `asOf` | 06:00 |
| `archiveAndPurgeJob` | `archiveFilesStep` → `purgeStep` | `runDate` | Sunday 02:00 |
| `seedOrdersJob` | `loadOrdersStep` (`FlatFileItemReader` → `JdbcBatchItemWriter`) | `file`, `checksum` | Demo start-up |

Restarting launches the same job instance again: Spring Batch skips completed steps and resumes the failed step from
its last commit. Rows skipped by `parseAndStageStep` are kept in `file_errors`, including those of the chunk that was
rolled back when the skip limit was reached. Executions left `STARTED` by a crash are marked `FAILED` at start-up so
they can be restarted. All entry points share one lock, so jobs never overlap.

A failed or stopped run needs action until one of three things happens, and only those that still need it are
warned about on the dashboard (`JobView.needsAction`):

- it was restarted (`restartedAs`, a later execution of the same job instance);
- it was replaced (`supersededBy`): for a file, another file of the same source and business date, received later,
  loaded as `COMPLETED` or `COMPLETED_WITH_ERRORS`. The rule lives only in `ReplacementFiles` (core) and is used by the
  dashboard, the batch monitor, the failure explanation and the assistant. A replaced run cannot be restarted (400),
  because loading both files would count the day's money twice;
- someone acknowledged it (`acknowledged`): `POST /api/batch/executions/{id}/acknowledge` with a required note, any
  role, `FAILED` or `STOPPED` only, once per execution (a second one is 409). Stored in `batch_execution_acks` (V5)
  with the user and time. The note is shown in the monitor but not sent to the model.

## Security

- `POST /api/auth/login` checks the bcrypt password and returns an HS256 JWT (10 hours) with the user id and role.
- Roles: `ACCOUNTANT`, `APPROVER` (chief accountant), `ADMIN`. Changing a source's column mapping needs `ADMIN` or
  `APPROVER`; approving write-offs needs `APPROVER`; approving or rejecting a fee schedule needs `APPROVER` or `ADMIN`
  (not the proposer); the assistant gives `ADMIN` no amounts.
- The legacy OMS has its own staff accounts (`oms.staff`), as it did before ReconHub.

## AI

`AiGateway` builds the Spring AI `AnthropicChatModel` only when `ANTHROPIC_API_KEY` is set, with default options that
leave out `temperature`, `top_p` and `top_k`. Every call goes through it: it checks the per-user daily limit, records
tokens and latency in `ai_requests`, and returns empty on an error or a refusal so the caller keeps the rule result.
Names and phone numbers are masked (`Redactor`) before a prompt is built. The model never changes data: suggestions
are shown to a person, and the assistant can only call six read-only tools.
