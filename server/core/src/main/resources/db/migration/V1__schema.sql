-- ReconHub schema.
-- The OMS tables live in their own schema to mimic a separate legacy system that ReconHub only reads.

create schema if not exists oms;

create table oms.orders (
    code           varchar(20)  primary key,
    channel        varchar(10)  not null,             -- WEBSITE | SHOPEE | TIKTOK
    channel_ref    varchar(40),                       -- marketplace order id
    customer_name  varchar(120) not null,
    phone          varchar(20),
    amount         numeric(14,0) not null,
    cod_amount     numeric(14,0) not null default 0,
    pay_via        varchar(10)  not null,             -- GHN | GHTK | SHOPEE | TIKTOK | VCB | MOMO
    tracking_code  varchar(40),
    status         varchar(12)  not null,             -- NEW | SHIPPING | DELIVERED | CANCELLED | RETURNED
    remote_area    boolean      not null default false,
    created_at     timestamptz  not null,
    delivered_at   timestamptz,
    note           varchar(255)
);
create index ix_orders_pay_via_created on oms.orders (pay_via, created_at);
create index ix_orders_tracking on oms.orders (tracking_code);
create index ix_orders_channel_ref on oms.orders (channel_ref);
create index ix_orders_status_delivered on oms.orders (status, delivered_at);

create table app_users (
    id             varchar(8)   primary key,
    username       varchar(40)  not null unique,
    password_hash  varchar(100) not null,
    full_name      varchar(80)  not null,
    role           varchar(12)  not null,             -- ACCOUNTANT | APPROVER | ADMIN
    title          varchar(60)  not null,
    email          varchar(120)
);

create table sources (
    code           varchar(10)  primary key,
    name           varchar(80)  not null,
    kind           varchar(12)  not null,             -- CARRIER | MARKETPLACE | BANK | EWALLET
    status         varchar(8)   not null,             -- ACTIVE | DRAFT
    channel        varchar(120) not null,
    file_pattern   varchar(80)  not null,
    file_regex     varchar(160) not null,
    schedule       varchar(80)  not null,
    expected_hour  integer,
    payout_days    varchar(20),                       -- ISO weekdays for periodic payouts, e.g. "1,4"
    encoding       varchar(20)  not null,
    date_format    varchar(40)  not null,
    delimiter      char(1)      not null default ',',
    amount_mode    varchar(5)   not null default 'NET', -- NET: amount column is after fee; GROSS: before fee
    partner_email  varchar(120),
    headers        jsonb        not null,
    sample_rows    jsonb        not null,
    mapping        jsonb        not null,
    fee_rules      jsonb        not null,
    updated_at     timestamptz,
    updated_by     varchar(80)
);

create table import_files (
    id                bigserial    primary key,
    source_code       varchar(10)  not null references sources (code),
    file_name         varchar(200) not null,
    stored_path       varchar(400),
    received_at       timestamptz  not null,
    business_date     date         not null,
    status            varchar(24)  not null,          -- PROCESSING | COMPLETED | COMPLETED_WITH_ERRORS | FAILED | DUPLICATE
    total_rows        integer      not null default 0,
    error_rows        integer      not null default 0,
    amount_total      numeric(16,0) not null default 0,
    checksum          varchar(64)  not null,
    job_execution_id  bigint,
    message           varchar(500),
    uploaded_by       varchar(80)
);
-- A file may be received twice, but only one copy is ever loaded.
create unique index ux_import_files_checksum on import_files (checksum) where status <> 'DUPLICATE';
create index ix_import_files_received on import_files (received_at desc);

create table file_errors (
    id           bigserial    primary key,
    file_id      bigint       not null references import_files (id) on delete cascade,
    row_no       integer      not null,
    column_name  varchar(80),
    value        varchar(200),
    reason       varchar(200) not null
);
create index ix_file_errors_file on file_errors (file_id);

create table match_groups (
    id           bigserial    primary key,
    source_code  varchar(10)  not null,
    method       varchar(16)  not null,               -- EXACT | AMOUNT_DATE | MANY_TO_ONE | MANUAL | AI_CONFIRMED
    confidence   numeric(4,3) not null,
    run_date     date         not null,
    created_at   timestamptz  not null,
    created_by   varchar(80)  not null
);

create table payment_lines (
    id              bigserial    primary key,
    source_code     varchar(10)  not null references sources (code),
    file_id         bigint       not null references import_files (id),
    row_no          integer      not null,
    external_ref    varchar(60)  not null,
    order_ref       varchar(60),
    tracking_code   varchar(60),
    description     varchar(400),
    amount          numeric(14,0) not null,           -- money actually received (after fee)
    fee             numeric(14,0) not null default 0,
    txn_at          timestamptz  not null,
    status          varchar(12)  not null default 'NEW', -- NEW | MATCHED | EXCEPTION | DUPLICATE
    match_group_id  bigint references match_groups (id),
    fee_checked     boolean      not null default false,
    created_at      timestamptz  not null default now()
);
create index ix_lines_source_status on payment_lines (source_code, status);
create index ix_lines_source_ref on payment_lines (source_code, external_ref);
create index ix_lines_file on payment_lines (file_id);
create index ix_lines_group on payment_lines (match_group_id);

create table match_items (
    id               bigserial    primary key,
    group_id         bigint       not null references match_groups (id) on delete cascade,
    order_code       varchar(20)  references oms.orders (code),
    payment_line_id  bigint       references payment_lines (id),
    amount           numeric(14,0) not null
);
create index ix_match_items_order on match_items (order_code);
create index ix_match_items_line on match_items (payment_line_id);

create sequence exception_seq;

create table recon_exceptions (
    id            varchar(20)  primary key,
    type          varchar(20)  not null,              -- UNMATCHED_PAYMENT | UNPAID_ORDER | AMOUNT_DIFF | FEE_DIFF | DUPLICATE
    source_code   varchar(10)  not null references sources (code),
    status        varchar(20)  not null,              -- OPEN | IN_REVIEW | WRITE_OFF_PENDING | RESOLVED | WRITTEN_OFF
    diff_amount   numeric(14,0) not null,
    run_date      date         not null,
    created_at    timestamptz  not null,
    sla_due_at    timestamptz  not null,
    resolved_at   timestamptz,
    assignee_id   varchar(8)   references app_users (id),
    meta          jsonb        not null default '{}'::jsonb,
    ai            jsonb,
    write_off     jsonb,
    version       integer      not null default 0
);
create index ix_exceptions_status_sla on recon_exceptions (status, sla_due_at);
create index ix_exceptions_run_date on recon_exceptions (run_date, source_code);

create table exception_orders (
    exception_id  varchar(20)  not null references recon_exceptions (id) on delete cascade,
    order_code    varchar(20)  not null references oms.orders (code),
    primary key (exception_id, order_code)
);
create index ix_exception_orders_order on exception_orders (order_code);

create table exception_payments (
    exception_id     varchar(20) not null references recon_exceptions (id) on delete cascade,
    payment_line_id  bigint      not null references payment_lines (id),
    position         integer     not null default 0,
    primary key (exception_id, payment_line_id)
);
create index ix_exception_payments_line on exception_payments (payment_line_id);

create table exception_actions (
    id            bigserial    primary key,
    exception_id  varchar(20)  not null references recon_exceptions (id) on delete cascade,
    at            timestamptz  not null,
    actor         varchar(80)  not null,
    kind          varchar(24)  not null,
    note          varchar(500)
);
create index ix_exception_actions_ex on exception_actions (exception_id, at);

create table ai_requests (
    id             bigserial    primary key,
    user_id        varchar(8),
    feature        varchar(30)  not null,
    model          varchar(60),
    input_tokens   integer      not null default 0,
    output_tokens  integer      not null default 0,
    latency_ms     integer      not null default 0,
    ok             boolean      not null,
    at             timestamptz  not null default now()
);
create index ix_ai_requests_user_day on ai_requests (user_id, at);
