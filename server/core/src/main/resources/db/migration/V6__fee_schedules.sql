-- Versioned fee schedules per source (roadmap 1.2). A row is a proposal until a chief accountant or admin approves it;
-- approval gives it the next version number of its source. The schedule that applies to a payment line is the approved
-- one with the latest effective_from on or before the line's transaction date (ties: the higher version).
--
-- components: parts computed one by one, each rounded half-up to the đồng, then added up.
--   {"label": "...", "kind": "FIXED",   "amount": 22000, "when": {...}}
--   {"label": "...", "kind": "PERCENT", "rate": 0.5, "base": "COD" | "ORDER_AMOUNT" | "LINE_GROSS", "min": 5000, "when": {...}}
--   when (optional): {"remoteArea": true} or {"base": "...", "above": 1500000} (strictly greater) / {"base": "...", "atMost": 900000}
create table fee_schedules (
    id              bigserial    primary key,
    source_code     varchar(10)  not null references sources (code),
    version_no      integer,
    status          varchar(10)  not null,             -- PROPOSED | APPROVED | REJECTED
    effective_from  date         not null,
    components      jsonb        not null,
    note            varchar(500) not null,
    from_exception  varchar(20)  references recon_exceptions (id),
    proposed_by     varchar(8)   references app_users (id),   -- null for the initial schedules
    proposed_at     timestamptz  not null,
    decided_by      varchar(8)   references app_users (id),
    decided_at      timestamptz,
    decision_note   varchar(500),
    version         integer      not null default 0,
    unique (source_code, version_no),
    check ((status = 'APPROVED') = (version_no is not null))
);
create index ix_fee_schedules_source on fee_schedules (source_code, status, effective_from desc);

-- Version 1: exactly the formulas FeeCalculator used before, applying from the start (2000-01-01).
insert into fee_schedules (source_code, version_no, status, effective_from, components, note, proposed_at, decided_at) values
('GHN', 1, 'APPROVED', date '2000-01-01',
 '[{"label":"Cước giao","kind":"FIXED","amount":22000},
   {"label":"Phí thu hộ","kind":"PERCENT","rate":0.5,"base":"COD","min":5000},
   {"label":"Phụ phí vùng xa","kind":"FIXED","amount":8000,"when":{"remoteArea":true}}]',
 'Biểu phí ban đầu, chuyển từ công thức trong FeeCalculator', now(), now()),
('GHTK', 1, 'APPROVED', date '2000-01-01',
 '[{"label":"Phí giao hàng, đơn đến 900.000 ₫","kind":"FIXED","amount":20000,"when":{"base":"ORDER_AMOUNT","atMost":900000}},
   {"label":"Phí giao hàng, đơn trên 900.000 ₫","kind":"FIXED","amount":30000,"when":{"base":"ORDER_AMOUNT","above":900000}}]',
 'Biểu phí ban đầu, chuyển từ công thức trong FeeCalculator', now(), now()),
('SHOPEE', 1, 'APPROVED', date '2000-01-01',
 '[{"label":"Phí cố định","kind":"PERCENT","rate":4,"base":"ORDER_AMOUNT"},
   {"label":"Phí thanh toán","kind":"PERCENT","rate":4.91,"base":"ORDER_AMOUNT"}]',
 'Biểu phí ban đầu, chuyển từ công thức trong FeeCalculator', now(), now()),
('TIKTOK', 1, 'APPROVED', date '2000-01-01',
 '[{"label":"Phí giao dịch","kind":"PERCENT","rate":5,"base":"ORDER_AMOUNT"}]',
 'Biểu phí ban đầu, chuyển từ công thức trong FeeCalculator', now(), now()),
('MOMO', 1, 'APPROVED', date '2000-01-01',
 '[{"label":"Phí giao dịch ví","kind":"PERCENT","rate":1,"base":"LINE_GROSS"}]',
 'Biểu phí ban đầu, chuyển từ công thức trong FeeCalculator', now(), now());
