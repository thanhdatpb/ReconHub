-- Staff accounts of the legacy OMS (its own login, separate from ReconHub's app_users).
-- Passwords are unsalted SHA-256 hex, as the old system stored them. Demo account: sale / sale123.
create table oms.staff (
    username         varchar(40)  primary key,
    password_sha256  char(64)     not null,
    full_name        varchar(80)  not null
);

insert into oms.staff (username, password_sha256, full_name) values
  ('sale', encode(sha256('sale123'::bytea), 'hex'), 'Võ Thị Sáu (bán hàng)');
