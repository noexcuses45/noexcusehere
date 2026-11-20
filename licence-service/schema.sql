-- Unlock codes for Ultimate Media Manager.
--
-- The app is free to copy; what is sold is a code. This is the list of codes, and of the devices
-- each code has been used on. Nobody can read or change any of it from outside: the tables are
-- shut to everyone except the code service itself (the "licence" function) and the owner of
-- this database working in the dashboard.
--
-- Safe to run more than once.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.licence_codes (
  id            uuid primary key default gen_random_uuid(),
  code          text not null unique check (code ~ '^[A-HJKMNP-Z2-9]{15}$'),
  product       text not null default 'umm',
  -- How many devices this code unlocks.
  max_devices   int  not null default 3 check (max_devices between 1 and 50),
  -- For the seller's own use: who it was sold to, an order number.
  note          text not null default '',
  -- Set to true to stop a code working (after a refund, say). Devices using it lock again
  -- the next time they look, within about a week.
  switched_off  boolean not null default false,
  created_at    timestamptz not null default now()
);

create table if not exists public.licence_devices (
  code_id     uuid not null references public.licence_codes (id) on delete cascade,
  -- A fingerprint the app makes of the device. It says nothing about the device or its owner.
  device      text not null check (device ~ '^[0-9a-f]{32}$'),
  platform    text not null default '',
  version     text not null default '',
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  primary key (code_id, device)
);

-- The key the service signs its answers with. One row, made by the service the first time it runs.
create table if not exists public.licence_secret (
  id           int primary key default 1 check (id = 1),
  private_jwk  jsonb not null,
  public_spki  text not null,
  created_at   timestamptz not null default now()
);

alter table public.licence_codes   enable row level security;
alter table public.licence_devices enable row level security;
alter table public.licence_secret  enable row level security;
-- No policies on purpose: with row level security on and no policy, nobody using the public
-- keys can see a single row.
revoke all on public.licence_codes, public.licence_devices, public.licence_secret from anon, authenticated;

-- Puts a code to use on a device. Answers with what happened:
--   ok       the device is (now, or already) one of the code's devices
--   unknown  there is no such code
--   off      the code has been switched off
--   full     the code is already on as many devices as it unlocks
create or replace function public.licence_unlock(p_code text, p_device text, p_platform text, p_version text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  c    public.licence_codes%rowtype;
  used int;
begin
  -- "for update" makes two devices arriving at the same moment take turns, so a code can
  -- never end up on more devices than it unlocks.
  select * into c from public.licence_codes where code = p_code for update;
  if not found then
    return jsonb_build_object('status', 'unknown');
  end if;
  if c.switched_off then
    return jsonb_build_object('status', 'off');
  end if;
  if exists (select 1 from public.licence_devices d where d.code_id = c.id and d.device = p_device) then
    update public.licence_devices d
       set last_seen = now(), platform = p_platform, version = p_version
     where d.code_id = c.id and d.device = p_device;
    return jsonb_build_object('status', 'ok', 'code_id', c.id, 'hint', right(c.code, 5), 'product', c.product);
  end if;
  select count(*) into used from public.licence_devices d where d.code_id = c.id;
  if used >= c.max_devices then
    return jsonb_build_object('status', 'full', 'devices', c.max_devices);
  end if;
  insert into public.licence_devices (code_id, device, platform, version)
  values (c.id, p_device, p_platform, p_version);
  return jsonb_build_object('status', 'ok', 'code_id', c.id, 'hint', right(c.code, 5), 'product', c.product);
end;
$$;

-- Whether a device that was unlocked earlier still is:
--   ok    yes
--   off   its code has been switched off
--   gone  the code no longer exists, or the device was taken off it (to free a place)
create or replace function public.licence_check(p_code_id uuid, p_device text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  c public.licence_codes%rowtype;
begin
  select * into c from public.licence_codes where id = p_code_id;
  if not found then
    return 'gone';
  end if;
  if c.switched_off then
    return 'off';
  end if;
  update public.licence_devices d set last_seen = now() where d.code_id = c.id and d.device = p_device;
  if not found then
    return 'gone';
  end if;
  return 'ok';
end;
$$;

-- Makes new codes and returns them, written the way they are handed out (ABCDE-FGHJK-MNPQR).
--   select * from licence_mint(5, 'batch for November');
create or replace function public.licence_mint(p_count int default 1, p_note text default '', p_max_devices int default 3)
returns setof text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  made text;
  b    int;
begin
  if p_count < 1 or p_count > 500 then
    raise exception 'Make between 1 and 500 codes at a time';
  end if;
  for i in 1..p_count loop
    loop
      made := '';
      while length(made) < 15 loop
        b := get_byte(gen_random_bytes(1), 0);
        -- 248 is 8 x 31, so every one of the 31 characters is equally likely.
        if b < 248 then
          made := made || substr(alphabet, (b % 31) + 1, 1);
        end if;
      end loop;
      begin
        insert into public.licence_codes (code, note, max_devices) values (made, p_note, p_max_devices);
        exit;
      exception when unique_violation then
        null; -- the same code twice is next to impossible; if it happens, make another
      end;
    end loop;
    return next substr(made, 1, 5) || '-' || substr(made, 6, 5) || '-' || substr(made, 11, 5);
  end loop;
end;
$$;

-- The codes at a glance, for the dashboard: how many devices each is on.
create or replace view public.licence_overview
with (security_invoker = true) as
select substr(c.code, 1, 5) || '-' || substr(c.code, 6, 5) || '-' || substr(c.code, 11, 5) as code,
       c.note,
       (select count(*) from public.licence_devices d where d.code_id = c.id) as devices_in_use,
       c.max_devices,
       c.switched_off,
       c.created_at,
       (select max(d.last_seen) from public.licence_devices d where d.code_id = c.id) as last_used
  from public.licence_codes c
 order by c.created_at desc;

revoke all on public.licence_overview from anon, authenticated;
revoke all on function public.licence_unlock(text, text, text, text) from public, anon, authenticated;
revoke all on function public.licence_check(uuid, text)               from public, anon, authenticated;
revoke all on function public.licence_mint(int, text, int)            from public, anon, authenticated;
grant execute on function public.licence_unlock(text, text, text, text) to service_role;
grant execute on function public.licence_check(uuid, text)               to service_role;
