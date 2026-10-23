# ReconHub runbook

Commands assume the repository root and the Docker Compose stack.

## Start, stop, reset

```bash
docker compose up -d --build        # start or update
docker compose ps                   # all services should be Up; datagen exits 0 after the first run
docker compose logs -f batch api    # follow the two Spring Boot apps
docker compose stop                 # stop, keep data
docker compose down -v              # remove containers AND data (database, /data volume): next start is a fresh demo
```

On the first start the batch app logs `Demo backfill: 139 files over 31 days` and then `Demo history: ...`.
`/data/.backfilled` marks that this was done; it is not repeated on later starts.
Set `DEMO_ENABLED=false` in `.env` to start with an empty system and feed files yourself.

## Feed a partner file

Drop it into the inbox; the batch app polls every 20 seconds and loads files that stopped changing 5 seconds ago.

```bash
docker compose cp GHN_DOISOAT_20261024.xlsx batch:/data/inbox/
docker compose logs -f batch
```

File names must match the source's pattern (`GHN_DOISOAT_yyyyMMdd*.xlsx`, `GHTK_COD_yyyyMMdd*.csv`,
`Shopee_Income_yyyyMMdd*.xlsx`, `TTS_Settlement_yyyyMMdd*.csv`, `VCB_SaoKe_yyyyMMdd*.csv`, `MOMO_Settlement_yyyyMMdd*.csv`).
Unknown names and sources that are not active are moved to `/data/rejected`. The same bytes are never loaded twice.

## Upload a bank statement for a new order

1. In the legacy OMS (http://localhost:8088/oms/, `sale` / `sale123`) create an order paid via `VCB`; note its code, e.g. `DH2610240001`.
2. Write a Vietcombank statement that pays it. Banks send Windows-1258 with decomposed tone marks, so use the helper:

   ```bash
   docker compose run --rm -v "$PWD:/out" --entrypoint python datagen bank_statement.py \
     DH2610240001:388000 --out /out/VCB_SaoKe_20261024_chieu.csv
   ```

   (or `python3 tools/datagen/bank_statement.py DH2610240001:388000` after `pip install -r tools/datagen/requirements.txt`).
3. Upload it on the "Nhận file" screen with source Vietcombank. Within seconds the file is `Đã nạp`, the line is
   matched `EXACT`, and the OMS order page shows "Đã khớp".

## Scan an order's VietQR code with a banking app

The QR is built locally (no outside service) for the account in `.env`. The sample account `1234567890` does not
exist, so a banking app shows an error for it; to check a real scan, use an account you own.

1. In `.env` set `VIETQR_BIN` (6-digit NAPAS BIN, e.g. `970436` Vietcombank, `970422` MB, `970415` VietinBank),
   `VIETQR_ACCOUNT` and `VIETQR_ACCOUNT_NAME` (no diacritics). Do not commit `.env`.
2. `docker compose up -d legacy-oms api` (only the environment changes, no rebuild needed).
3. Open a VCB order in the legacy OMS (or create one paid via `VCB`): the page shows the QR, BIN, account, amount and
   the order code as the description. The same code is at `GET /api/orders/{code}/vietqr.png` (accountant or chief
   accountant token).
4. Scan it with the banking app's QR scanner. The transfer screen must show your account and holder name (the bank
   looks the name up), the order amount, and the order code as the description. Cancel without confirming, or confirm a
   small test order created for the purpose.
5. Put the sample values back (or remove the three lines) when done.

## A failed job

1. "Theo dõi batch" lists the failed execution; "AI giải thích" shows the cause and what to do.
2. Fix the cause (mail server back, corrected file, source mapping), then "Chạy lại". Completed steps are skipped.
3. A file that failed because its rows are wrong fails again on restart; load the corrected file instead
   (it has a different checksum).

Mail outage drill: `docker compose stop mailpit`, "Chạy ngay" on `agingAndReportJob`, it fails at `emailReportStep`;
`docker compose start mailpit`, "Chạy lại": only `emailReportStep` runs and the e-mail appears in Mailpit.

## Check matching quality

```bash
docker compose run --rm --entrypoint python datagen eval_matching.py \
  --truth /data/truth/truth.csv --db postgresql://reconhub:reconhub@postgres:5432/reconhub
```

Prints precision of automatic matches, the automation rate (overall and per source) and where each planted anomaly
ended up.

To see what VietQR would change, regenerate the demo with a share of Vietcombank customers paying by the order's code,
then measure again; reset with `0` afterwards so the data matches CI:

```bash
docker compose down -v && DATAGEN_QR_RATE=0.6 docker compose up -d   # wait for /data/.backfilled, then eval as above
docker compose down -v && docker compose up -d                        # back to the default data
```

## Turn on the LLM

Set `ANTHROPIC_API_KEY` (and optionally `ANTHROPIC_MODEL`) in `.env`, then `docker compose up -d api batch`.
The top bar shows "AI: LLM". Usage per call is in the `ai_requests` table; each user is limited to 200 calls a day.
Without a key, or when the API returns an error or declines, every feature falls back to its rules.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Upload says the command queue did not respond | Artemis is down or blocking producers. `docker compose logs artemis`; the file is kept and loaded by the batch app once the broker is back (orphan sweep, within a minute). |
| 502 from the console | The api container is starting or restarting; wait for `Started ApiApplication` in `docker compose logs api`. |
| Login says the session expired | The JWT is older than 10 hours or `JWT_SECRET` changed; sign in again. |
| No files load | Check the file name pattern and that the source is ACTIVE on "Cấu hình nguồn"; look for `moved to rejected` in the batch log. |
| Port already in use | Change `WEB_PORT`, `OMS_PORT`, `POSTGRES_PORT`... in `.env`. |
| Database inspection | `docker compose exec postgres psql -U reconhub` |
