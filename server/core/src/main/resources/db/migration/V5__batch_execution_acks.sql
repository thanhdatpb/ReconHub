-- A person's note that a failed or stopped batch run needs no further action, so the dashboard stops warning about it.
-- One per execution: acknowledging twice is refused, the first note stays.
create table batch_execution_acks (
    job_execution_id  bigint       primary key references batch_job_execution (job_execution_id) on delete cascade,
    acknowledged_by   varchar(8)   not null references app_users (id),
    acknowledged_at   timestamptz  not null,
    note              varchar(500) not null check (length(btrim(note)) > 0)
);
