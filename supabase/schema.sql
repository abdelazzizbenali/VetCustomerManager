-- =====================================================================
--  VetCustomerManager 4.0 - Supabase database schema
--  -------------------------------------------------------------------
--  HOW TO USE:
--    1. Create a (free) project on https://supabase.com
--    2. In the project dashboard open:  SQL Editor  ->  New query
--    3. Paste this whole file and press  "Run"
--    4. Go to  Project Settings -> API  and copy:
--         - Project URL        (https://xxxxxxxx.supabase.co)
--         - anon public key    (the app will ask for both on 1st launch)
--    That's it. The desktop app (and later the mobile apps) sync
--    against these tables through the Supabase REST API.
--
--  Sync model (offline-first):
--    * Every row owns a stable UUID (`uuid`) used for upserts, so a row
--      created offline on a PC or a phone can never collide on the server.
--    * `updated_at` is maintained automatically by a trigger and is what
--      the apps use to download only the changes since their last sync.
--    * Deletes are soft-deletes (`is_deleted = true`) so every device
--      learns about them instead of rows silently disappearing.
-- =====================================================================

begin;

-- ---------------------------------------------------------------------
-- 0. UUID generation (pgcrypto is available on every Supabase project)
-- ---------------------------------------------------------------------
create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------
-- 1. app_meta : used by apps to "ping" the database and verify the
--    schema version before they start synchronising.
-- ---------------------------------------------------------------------
create table if not exists public.app_meta (
    id             smallint primary key default 1 check (id = 1),
    app            text not null default 'VetCustomerManager',
    schema_version integer not null default 1,
    created_at     timestamptz not null default now()
);
insert into public.app_meta (id, app, schema_version)
values (1, 'VetCustomerManager', 1)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- 2. medicines : products / medicines stock with partial-size support
--    (a bottle of 100 ml can be sold 5 ml at a time, exactly like the
--     old desktop version did with m_size / m_full_size)
-- ---------------------------------------------------------------------
create table if not exists public.medicines (
    id                  bigint generated always as identity primary key,
    uuid                uuid        not null default gen_random_uuid() unique,
    barcode             text        not null default '[UNKNOWN BARCODE]',
    name                text        not null default '[UNKNOWN NAME]',
    type                text        not null default '[UNKNOWN TYPE]',
    size                numeric(12,2) not null default 0,        -- content left in the open unit
    full_size           numeric(12,2) not null default 0,        -- content of a brand new unit
    buy_price           numeric(12,2) not null default 0,
    sell_price          numeric(12,2) not null default 0,
    expiry_date         date        not null default '9999-12-31',
    stock               integer     not null default 0,          -- sealed units in stock
    seller              text        not null default '[UNKNOWN SELLER]',
    description         text        not null default '[NO DESCRIPTION]',
    low_stock_threshold integer     not null default 3,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    is_deleted          boolean     not null default false
);
comment on table public.medicines is 'Medicine / product stock managed by VetCustomerManager';

-- ---------------------------------------------------------------------
-- 3. clients
-- ---------------------------------------------------------------------
create table if not exists public.clients (
    id          bigint generated always as identity primary key,
    uuid        uuid        not null default gen_random_uuid() unique,
    name        text        not null default '[UNKNOWN NAME]',
    phone       text        not null default '',
    address     text        not null default '',
    description text        not null default '[NO DESCRIPTION]',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    is_deleted  boolean     not null default false
);

-- ---------------------------------------------------------------------
-- 4. client_animals : BOTH registered pets (a dog with a name, breed,
--    birth date...) AND livestock counters (45 sheep, 12 cows...).
--      category = 'pet'       -> quantity is ignored (always 1)
--      category = 'livestock' -> name/breed/gender/birth_date unused
-- ---------------------------------------------------------------------
create table if not exists public.client_animals (
    id          bigint generated always as identity primary key,
    uuid        uuid        not null default gen_random_uuid() unique,
    client_id   bigint      not null references public.clients(id) on delete cascade,
    category    text        not null default 'livestock'
                check (category in ('pet','livestock')),
    species     text        not null default 'Other',     -- Dog, Cat, Sheep, Cow, Goat, Chicken...
    name        text        not null default '',          -- pet name
    breed       text        not null default '',
    gender      text        not null default 'unknown',   -- male / female / unknown
    birth_date  date,
    quantity    integer     not null default 1 check (quantity >= 0),
    notes       text        not null default '',          -- vaccines, health notes...
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    is_deleted  boolean     not null default false
);
create index if not exists client_animals_client_idx on public.client_animals (client_id);

-- ---------------------------------------------------------------------
-- 5. transactions : the daily usage / purchases (your daily sales and
--    services). When a transaction carries a medicine and a quantity,
--    the stock of that medicine is deducted automatically by trigger
--    (same behaviour as the old SellMedicinePartial procedure).
-- ---------------------------------------------------------------------
create table if not exists public.transactions (
    id          bigint generated always as identity primary key,
    uuid        uuid        not null default gen_random_uuid() unique,
    client_id   bigint      not null references public.clients(id),
    medicine_id bigint      references public.medicines(id),
    created_at  timestamptz not null default now(),
    quantity    numeric(12,2) not null default 0,        -- content sold (ml / doses), 0 = service
    amount      numeric(12,2) not null default 0,        -- money (DA) asked for this line
    type        text        not null default 'Consultation', -- Consultation / Treatment / Product ...
    description text        not null default '[NO DESCRIPTION]',
    paid        boolean     not null default false,
    updated_at  timestamptz not null default now(),
    is_deleted  boolean     not null default false
);
create index if not exists transactions_client_idx   on public.transactions (client_id);
create index if not exists transactions_medicine_idx on public.transactions (medicine_id);
create index if not exists transactions_created_idx  on public.transactions (created_at);

-- ---------------------------------------------------------------------
-- 6. appointments
-- ---------------------------------------------------------------------
create table if not exists public.appointments (
    id               bigint generated always as identity primary key,
    uuid             uuid        not null default gen_random_uuid() unique,
    client_id        bigint      not null references public.clients(id) on delete cascade,
    appointment_date date        not null,
    is_done          boolean     not null default false,
    description      text        not null default '[NO DESCRIPTION]',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    is_deleted       boolean     not null default false
);
create index if not exists appointments_client_idx on public.appointments (client_id);
create index if not exists appointments_date_idx   on public.appointments (appointment_date);

-- ---------------------------------------------------------------------
-- 7. updated_at maintenance (drives incremental sync on every device)
-- ---------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_medicines_touch   on public.medicines;
create trigger trg_medicines_touch   before update on public.medicines
    for each row execute function public.touch_updated_at();

drop trigger if exists trg_clients_touch     on public.clients;
create trigger trg_clients_touch     before update on public.clients
    for each row execute function public.touch_updated_at();

drop trigger if exists trg_animals_touch     on public.client_animals;
create trigger trg_animals_touch     before update on public.client_animals
    for each row execute function public.touch_updated_at();

drop trigger if exists trg_transactions_touch on public.transactions;
create trigger trg_transactions_touch before update on public.transactions
    for each row execute function public.touch_updated_at();

drop trigger if exists trg_appointments_touch on public.appointments;
create trigger trg_appointments_touch before update on public.appointments
    for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------------
-- 8. Automatic stock deduction when a sale line is inserted.
--    Partial-size logic identical to the old MySQL SellMedicinePartial:
--      size = size - quantity
--      while size < 0: stock = stock - 1, size = size + full_size
--    A negative quantity restocks (useful for stock corrections).
-- ---------------------------------------------------------------------
create or replace function public.apply_stock_deduction()
returns trigger
language plpgsql
as $$
declare
    v_size numeric(12,2);
    v_full numeric(12,2);
    v_stock integer;
    v_remaining numeric(12,2);
    v_name text;
begin
    -- nothing to do for pure services (no medicine or no quantity)
    if new.medicine_id is null or new.quantity = 0 or coalesce(new.is_deleted, false) then
        return new;
    end if;

    select size, full_size, stock, name
      into v_size, v_full, v_stock, v_name
      from public.medicines
     where id = new.medicine_id
     for update;

    if not found then
        raise exception 'Medicine with id % does not exist', new.medicine_id;
    end if;

    if v_full is null or v_full <= 0 then
        -- the product has no partial-size information: count whole units
        if new.quantity > 0 and v_stock < ceil(new.quantity) then
            raise exception 'Not enough stock for "%" (% unit(s) left)', v_name, v_stock
                using errcode = 'P0001';
        end if;
        v_stock := v_stock - ceil(new.quantity);
        v_remaining := v_size;
    else
        v_remaining := coalesce(v_size, 0) - new.quantity;
        while v_remaining < 0 loop
            v_stock := v_stock - 1;
            v_remaining := v_remaining + v_full;
        end loop;
        while v_remaining >= v_full loop
            v_stock := v_stock + 1;
            v_remaining := v_remaining - v_full;
        end loop;
        if v_stock < 0 then
            raise exception 'Not enough stock for "%"', v_name
                using errcode = 'P0001';
        end if;
    end if;

    update public.medicines
       set stock = v_stock,
           size  = case when v_stock > 0 then greatest(v_remaining, 0) else 0 end
     where id = new.medicine_id;

    return new;
end;
$$;

drop trigger if exists trg_transactions_stock on public.transactions;
create trigger trg_transactions_stock
    before insert on public.transactions
    for each row execute function public.apply_stock_deduction();

-- ---------------------------------------------------------------------
-- 9. Indexes used by incremental sync (updated_at > last sync)
-- ---------------------------------------------------------------------
create index if not exists medicines_updated_idx     on public.medicines (updated_at);
create index if not exists clients_updated_idx       on public.clients (updated_at);
create index if not exists animals_updated_idx       on public.client_animals (updated_at);
create index if not exists transactions_updated_idx  on public.transactions (updated_at);
create index if not exists appointments_updated_idx  on public.appointments (updated_at);
create unique index if not exists medicines_barcode_u_idx
    on public.medicines (barcode)
    where barcode <> '[UNKNOWN BARCODE]' and is_deleted = false;

-- ---------------------------------------------------------------------
-- 10. Row Level Security
--     The desktop / mobile apps connect with the public "anon" key.
--     These policies give full access to that key, which is what the
--     old single-PC version implicitly had.
--
--     !! IMPORTANT - READ BEFORE GOING TO PRODUCTION !!
--     Anyone holding your anon key can read and modify all rows.
--     For a real clinic you should later enable Supabase Auth, create
--     one user per employee, and replace these policies with
--     "auth.uid() is not null" based policies. The apps only need the
--     anon key today, so permissive policies keep setup one-step.
-- ---------------------------------------------------------------------
alter table public.medicines       enable row level security;
alter table public.clients         enable row level security;
alter table public.client_animals  enable row level security;
alter table public.transactions    enable row level security;
alter table public.appointments    enable row level security;
alter table public.app_meta        enable row level security;

drop policy if exists vetms_all_medicines     on public.medicines;
create policy vetms_all_medicines     on public.medicines       for all to anon, authenticated using (true) with check (true);

drop policy if exists vetms_all_clients       on public.clients;
create policy vetms_all_clients       on public.clients         for all to anon, authenticated using (true) with check (true);

drop policy if exists vetms_all_animals       on public.client_animals;
create policy vetms_all_animals       on public.client_animals  for all to anon, authenticated using (true) with check (true);

drop policy if exists vetms_all_transactions  on public.transactions;
create policy vetms_all_transactions  on public.transactions    for all to anon, authenticated using (true) with check (true);

drop policy if exists vetms_all_appointments  on public.appointments;
create policy vetms_all_appointments  on public.appointments    for all to anon, authenticated using (true) with check (true);

drop policy if exists vetms_read_meta         on public.app_meta;
create policy vetms_read_meta         on public.app_meta        for select to anon, authenticated using (true);

commit;

-- Done. You should see "Success. No rows returned".
-- Optional performance-cleanup routine you may schedule later (pg_cron):
--   delete from public.transactions where is_deleted and updated_at < now() - interval '1 year';

-- ============================================================
--  v4.0 backend: camera scan audit trail (used by vetms-server)
-- ============================================================
begin;

create table if not exists public.scan_log (
    id          bigint generated always as identity primary key,
    barcode     text        not null,
    route       text        not null default 'notify',
    engine      text,
    device      text,
    scanned_at  timestamptz not null default now()
);

create index if not exists scan_log_scanned_at_idx on public.scan_log (scanned_at desc);
create index if not exists scan_log_barcode_idx    on public.scan_log (barcode);

alter table public.scan_log enable row level security;

drop policy if exists vetms_all_scan_log on public.scan_log;
create policy vetms_all_scan_log on public.scan_log
    for all to anon, authenticated using (true) with check (true);

commit;
