-- Social Scheduler backend schema.
--
-- One Supabase project = one workspace. Everyone listed in `members` shares
-- the same connected accounts and posts. Secrets (developer-app credentials
-- and access tokens) live in tables that have RLS enabled and NO policies, so
-- only the service role (the Edge Function) can read them.

create schema if not exists private;
grant usage on schema private to authenticated, service_role;

-- ---------------------------------------------------------------------------
-- Membership
-- ---------------------------------------------------------------------------
create table public.members (
  user_id    uuid primary key references auth.users (id) on delete cascade,
  role       text not null default 'member' check (role in ('owner', 'member')),
  email      text,
  created_at timestamptz not null default now()
);
alter table public.members enable row level security;

create function private.is_member() returns boolean
  language sql stable security definer set search_path = ''
as $$
  select exists (select 1 from public.members m where m.user_id = (select auth.uid()));
$$;

create function private.is_owner() returns boolean
  language sql stable security definer set search_path = ''
as $$
  select exists (
    select 1 from public.members m
    where m.user_id = (select auth.uid()) and m.role = 'owner');
$$;

revoke all on function private.is_member() from public;
revoke all on function private.is_owner() from public;
grant execute on function private.is_member() to authenticated, service_role;
grant execute on function private.is_owner() to authenticated, service_role;

create policy members_select on public.members
  for select to authenticated using ((select private.is_member()));

-- ---------------------------------------------------------------------------
-- Workspace config (service role only)
-- ---------------------------------------------------------------------------
create table public.app_config (
  id                   boolean primary key default true check (id),
  -- Shared secret the cron job presents to the Edge Function.
  cron_secret          text not null
                         default replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
  -- e.g. https://<project-ref>.supabase.co/functions/v1/scheduler
  function_url         text,
  -- claim-owner / add-member open a short window in which a user may be created.
  signup_window_until  timestamptz,
  -- Uploaded media is deleted this many days after a post is published.
  media_retention_days integer not null default 90 check (media_retention_days >= 1),
  -- Posts that are this late (worker outage) are failed instead of sent.
  max_late_minutes     integer not null default 360 check (max_late_minutes >= 5),
  updated_at           timestamptz not null default now()
);
alter table public.app_config enable row level security;
insert into public.app_config default values;

-- Developer-app credentials for each platform (service role only).
create table public.platform_credentials (
  platform      text primary key
                  check (platform in ('meta', 'tiktok', 'google', 'linkedin', 'anthropic')),
  client_id     text,
  client_secret text not null,
  -- Non-secret switches, e.g. {"direct_post": true} for TikTok.
  options       jsonb not null default '{}'::jsonb,
  updated_by    uuid references auth.users (id) on delete set null,
  updated_at    timestamptz not null default now()
);
alter table public.platform_credentials enable row level security;

-- ---------------------------------------------------------------------------
-- Connected social accounts
-- ---------------------------------------------------------------------------
create table public.social_accounts (
  id               uuid primary key default gen_random_uuid(),
  platform         text not null
                     check (platform in ('facebook', 'instagram', 'tiktok', 'youtube', 'linkedin')),
  external_id      text not null,
  name             text not null,
  username         text,
  avatar_url       text,
  status           text not null default 'active' check (status in ('active', 'needs_reconnect')),
  status_detail    text,
  meta             jsonb not null default '{}'::jsonb,
  token_expires_at timestamptz,
  connected_by     uuid references auth.users (id) on delete set null,
  connected_at     timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (platform, external_id)
);
alter table public.social_accounts enable row level security;

create policy social_accounts_select on public.social_accounts
  for select to authenticated using ((select private.is_member()));
create policy social_accounts_delete on public.social_accounts
  for delete to authenticated using ((select private.is_member()));

-- Access tokens (service role only).
create table public.social_account_tokens (
  account_id         uuid primary key references public.social_accounts (id) on delete cascade,
  access_token       text not null,
  refresh_token      text,
  expires_at         timestamptz,
  refresh_expires_at timestamptz,
  scope              text,
  updated_at         timestamptz not null default now()
);
alter table public.social_account_tokens enable row level security;

-- In-flight OAuth handshakes (service role only).
create table public.oauth_states (
  state      text primary key,
  user_id    uuid not null references auth.users (id) on delete cascade,
  platform   text not null check (platform in ('meta', 'tiktok', 'google', 'linkedin')),
  options    jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '15 minutes'
);
alter table public.oauth_states enable row level security;

-- ---------------------------------------------------------------------------
-- Posts
-- ---------------------------------------------------------------------------
create table public.posts (
  id           uuid primary key default gen_random_uuid(),
  created_by   uuid default auth.uid() references auth.users (id) on delete set null,
  caption      text not null default '',
  link_url     text,
  status       text not null default 'draft'
                 check (status in ('draft', 'scheduled', 'publishing', 'published', 'partial', 'failed')),
  scheduled_at timestamptz,
  published_at timestamptz,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  constraint posts_scheduled_needs_time check (status = 'draft' or scheduled_at is not null)
);
alter table public.posts enable row level security;
create index posts_status_time_idx on public.posts (status, scheduled_at);
create index posts_scheduled_at_idx on public.posts (scheduled_at);

create policy posts_all on public.posts
  for all to authenticated
  using ((select private.is_member())) with check ((select private.is_member()));

create table public.post_media (
  id           uuid primary key default gen_random_uuid(),
  post_id      uuid not null references public.posts (id) on delete cascade,
  position     integer not null default 0,
  kind         text not null check (kind in ('image', 'video')),
  storage_path text,          -- null once the file has been purged
  thumb_path   text,
  mime_type    text not null,
  size_bytes   bigint not null default 0,
  width        integer,
  height       integer,
  duration_ms  integer,
  created_at   timestamptz not null default now()
);
alter table public.post_media enable row level security;
create index post_media_post_idx on public.post_media (post_id, position);

create policy post_media_all on public.post_media
  for all to authenticated
  using ((select private.is_member())) with check ((select private.is_member()));

-- One row per destination. mode = 'auto' is published by the server;
-- mode = 'reminder' only raises a notification on the phone.
create table public.post_targets (
  id                    uuid primary key default gen_random_uuid(),
  post_id               uuid not null references public.posts (id) on delete cascade,
  platform              text not null
                          check (platform in ('facebook', 'instagram', 'tiktok', 'youtube', 'linkedin', 'other')),
  account_id            uuid references public.social_accounts (id) on delete set null,
  mode                  text not null default 'auto' check (mode in ('auto', 'reminder')),
  -- instagram: feed | reel | story    facebook: feed | reel    tiktok: direct | inbox
  -- youtube: video                     linkedin: feed
  post_type             text not null default 'feed',
  caption_override      text,
  options               jsonb not null default '{}'::jsonb,
  status                text not null default 'pending'
                          check (status in ('pending', 'processing', 'published', 'failed')),
  attempts              integer not null default 0,
  next_attempt_at       timestamptz,
  locked_until          timestamptz,
  state                 jsonb not null default '{}'::jsonb,
  external_id           text,
  permalink             text,
  error                 text,
  note                  text,
  processing_started_at timestamptz,
  published_at          timestamptz,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
alter table public.post_targets enable row level security;
create index post_targets_post_idx on public.post_targets (post_id);
create index post_targets_due_idx on public.post_targets (status, mode) where status in ('pending', 'processing');
create index post_targets_account_idx on public.post_targets (account_id);

create policy post_targets_all on public.post_targets
  for all to authenticated
  using ((select private.is_member())) with check ((select private.is_member()));

-- ---------------------------------------------------------------------------
-- Tools: saved hashtag groups / captions, and weekly posting slots
-- ---------------------------------------------------------------------------
create table public.snippets (
  id         uuid primary key default gen_random_uuid(),
  kind       text not null check (kind in ('hashtags', 'caption')),
  title      text not null,
  body       text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.snippets enable row level security;
create policy snippets_all on public.snippets
  for all to authenticated
  using ((select private.is_member())) with check ((select private.is_member()));

-- Preferred posting times, in the phone's local time.
create table public.posting_slots (
  id            uuid primary key default gen_random_uuid(),
  weekday       smallint not null check (weekday between 1 and 7),       -- 1 = Monday
  minute_of_day smallint not null check (minute_of_day between 0 and 1439),
  created_at    timestamptz not null default now(),
  unique (weekday, minute_of_day)
);
alter table public.posting_slots enable row level security;
create policy posting_slots_all on public.posting_slots
  for all to authenticated
  using ((select private.is_member())) with check ((select private.is_member()));

-- ---------------------------------------------------------------------------
-- Keep posts.status in step with its targets
-- ---------------------------------------------------------------------------
create function private.recompute_post_status(p_post uuid) returns void
  language plpgsql security definer set search_path = ''
as $$
declare
  cur        text;
  n_total    integer;
  n_pub      integer;
  n_fail     integer;
  n_proc     integer;
  n_pend     integer;
  last_pub   timestamptz;
  new_status text;
begin
  select status into cur from public.posts where id = p_post for update;
  if not found or cur = 'draft' then
    return;
  end if;

  select count(*),
         count(*) filter (where status = 'published'),
         count(*) filter (where status = 'failed'),
         count(*) filter (where status = 'processing'),
         count(*) filter (where status = 'pending'),
         max(published_at)
    into n_total, n_pub, n_fail, n_proc, n_pend, last_pub
    from public.post_targets
   where post_id = p_post;

  if n_total = 0 then
    return;
  end if;

  if n_pub = n_total then
    new_status := 'published';
  elsif n_proc + n_pend > 0 then
    if n_proc > 0 or n_pub > 0 or n_fail > 0 then
      new_status := 'publishing';
    else
      new_status := 'scheduled';
    end if;
  elsif n_pub > 0 then
    new_status := 'partial';
  else
    new_status := 'failed';
  end if;

  update public.posts
     set status = new_status,
         published_at = case when new_status in ('published', 'partial')
                             then coalesce(last_pub, now()) else null end,
         updated_at = now()
   where id = p_post
     and (status is distinct from new_status
          or (new_status in ('published', 'partial') and published_at is null));
end;
$$;

create function private.on_post_target_change() returns trigger
  language plpgsql security definer set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    perform private.recompute_post_status(old.post_id);
    return old;
  end if;
  perform private.recompute_post_status(new.post_id);
  return new;
end;
$$;

create trigger post_targets_status_sync
  after insert or delete or update of status on public.post_targets
  for each row execute function private.on_post_target_change();

create function private.touch_updated_at() returns trigger
  language plpgsql set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger post_targets_touch before update on public.post_targets
  for each row execute function private.touch_updated_at();
create trigger snippets_touch before update on public.snippets
  for each row execute function private.touch_updated_at();
create trigger social_accounts_touch before update on public.social_accounts
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- RPC: save a post with its media and targets in one transaction
-- ---------------------------------------------------------------------------
-- p_status is 'draft' or 'scheduled'. Runs with the caller's rights, so RLS
-- still decides who may write.
create function public.save_post(
  p_id           uuid,
  p_caption      text,
  p_link_url     text,
  p_status       text,
  p_scheduled_at timestamptz,
  p_media        jsonb,
  p_targets      jsonb
) returns uuid
  language plpgsql security invoker set search_path = ''
as $$
declare
  v_id  uuid := p_id;
  v_cur text;
begin
  if p_status not in ('draft', 'scheduled') then
    raise exception 'Status must be draft or scheduled.';
  end if;
  if p_status = 'scheduled' and p_scheduled_at is null then
    raise exception 'A scheduled post needs a date and time.';
  end if;
  if p_status = 'scheduled' and jsonb_array_length(coalesce(p_targets, '[]'::jsonb)) = 0 then
    raise exception 'Choose at least one account to post to.';
  end if;

  if v_id is null then
    insert into public.posts (caption, link_url, status, scheduled_at)
    values (coalesce(p_caption, ''), nullif(btrim(p_link_url), ''), 'draft', p_scheduled_at)
    returning id into v_id;
  else
    select status into v_cur from public.posts where id = v_id for update;
    if not found then
      raise exception 'Post not found.';
    end if;
    if v_cur in ('publishing', 'published', 'partial') then
      raise exception 'This post has already started publishing and can no longer be edited.';
    end if;
    -- Back to draft while the children are replaced, so the status trigger
    -- leaves the post alone until everything is in place.
    update public.posts
       set caption = coalesce(p_caption, ''),
           link_url = nullif(btrim(p_link_url), ''),
           status = 'draft',
           scheduled_at = p_scheduled_at,
           published_at = null,
           updated_at = now()
     where id = v_id;
    delete from public.post_targets where post_id = v_id;
    delete from public.post_media where post_id = v_id;
  end if;

  insert into public.post_media
    (post_id, position, kind, storage_path, thumb_path, mime_type, size_bytes, width, height, duration_ms)
  select v_id,
         (m.ord - 1)::integer,
         m.item ->> 'kind',
         m.item ->> 'storage_path',
         m.item ->> 'thumb_path',
         coalesce(m.item ->> 'mime_type', 'application/octet-stream'),
         coalesce((m.item ->> 'size_bytes')::bigint, 0),
         (m.item ->> 'width')::integer,
         (m.item ->> 'height')::integer,
         (m.item ->> 'duration_ms')::integer
    from jsonb_array_elements(coalesce(p_media, '[]'::jsonb)) with ordinality as m(item, ord);

  insert into public.post_targets
    (post_id, platform, account_id, mode, post_type, caption_override, options)
  select v_id,
         t.item ->> 'platform',
         (t.item ->> 'account_id')::uuid,
         coalesce(t.item ->> 'mode', 'auto'),
         coalesce(t.item ->> 'post_type', 'feed'),
         nullif(t.item ->> 'caption_override', ''),
         coalesce(t.item -> 'options', '{}'::jsonb)
    from jsonb_array_elements(coalesce(p_targets, '[]'::jsonb)) as t(item);

  if p_status = 'scheduled' then
    update public.posts set status = 'scheduled', updated_at = now() where id = v_id;
  end if;

  return v_id;
end;
$$;

revoke all on function public.save_post(uuid, text, text, text, timestamptz, jsonb, jsonb) from public, anon;
grant execute on function public.save_post(uuid, text, text, text, timestamptz, jsonb, jsonb)
  to authenticated, service_role;

-- RPC: put failed targets of a post back in the queue.
create function public.retry_post(p_post uuid) returns void
  language plpgsql security invoker set search_path = ''
as $$
begin
  update public.post_targets
     set status = 'pending', error = null, attempts = 0, next_attempt_at = null,
         locked_until = null, state = '{}'::jsonb, processing_started_at = null
   where post_id = p_post and status = 'failed';
  -- A retry is deliberate, so it must not trip the "too late" guard.
  update public.posts set scheduled_at = now(), updated_at = now()
   where id = p_post and status in ('scheduled', 'publishing');
end;
$$;

revoke all on function public.retry_post(uuid) from public, anon;
grant execute on function public.retry_post(uuid) to authenticated, service_role;

-- ---------------------------------------------------------------------------
-- RPC: developer-app credentials (secrets are write-only from the app)
-- ---------------------------------------------------------------------------
create function public.credentials_status()
  returns table (platform text, client_id text, configured boolean, options jsonb, updated_at timestamptz)
  language plpgsql stable security definer set search_path = ''
as $$
begin
  if not private.is_member() then
    raise exception 'Not allowed.' using errcode = '42501';
  end if;
  return query
    select c.platform, c.client_id, true, c.options, c.updated_at from public.platform_credentials c;
end;
$$;

create function public.set_platform_credentials(
  p_platform      text,
  p_client_id     text,
  p_client_secret text,
  p_options       jsonb default '{}'::jsonb
) returns void
  language plpgsql security definer set search_path = ''
as $$
begin
  if not private.is_owner() then
    raise exception 'Only the owner can change app credentials.' using errcode = '42501';
  end if;
  if p_platform not in ('meta', 'tiktok', 'google', 'linkedin', 'anthropic') then
    raise exception 'Unknown platform.';
  end if;
  if coalesce(btrim(p_client_secret), '') = '' then
    raise exception 'The secret cannot be empty.';
  end if;
  insert into public.platform_credentials (platform, client_id, client_secret, options, updated_by, updated_at)
  values (p_platform, nullif(btrim(p_client_id), ''), btrim(p_client_secret),
          coalesce(p_options, '{}'::jsonb), auth.uid(), now())
  on conflict (platform) do update
    set client_id = excluded.client_id,
        client_secret = excluded.client_secret,
        options = excluded.options,
        updated_by = excluded.updated_by,
        updated_at = now();
end;
$$;

create function public.clear_platform_credentials(p_platform text) returns void
  language plpgsql security definer set search_path = ''
as $$
begin
  if not private.is_owner() then
    raise exception 'Only the owner can change app credentials.' using errcode = '42501';
  end if;
  delete from public.platform_credentials where platform = p_platform;
end;
$$;

revoke all on function public.credentials_status() from public, anon;
revoke all on function public.set_platform_credentials(text, text, text, jsonb) from public, anon;
revoke all on function public.clear_platform_credentials(text) from public, anon;
grant execute on function public.credentials_status() to authenticated;
grant execute on function public.set_platform_credentials(text, text, text, jsonb) to authenticated;
grant execute on function public.clear_platform_credentials(text) to authenticated;

-- ---------------------------------------------------------------------------
-- Worker RPCs (service role only)
-- ---------------------------------------------------------------------------
-- Atomically hand out due targets so overlapping worker runs never double-post.
create function public.claim_due_targets(p_limit integer default 10)
  returns setof public.post_targets
  language plpgsql security definer set search_path = ''
as $$
begin
  return query
  update public.post_targets t
     set locked_until = now() + interval '4 minutes',
         status = 'processing',
         processing_started_at = coalesce(t.processing_started_at, now())
   where t.id in (
     select t2.id
       from public.post_targets t2
       join public.posts p on p.id = t2.post_id
      where t2.mode = 'auto'
        and t2.status in ('pending', 'processing')
        and p.status in ('scheduled', 'publishing')
        and p.scheduled_at <= now()
        and coalesce(t2.next_attempt_at, '-infinity'::timestamptz) <= now()
        and coalesce(t2.locked_until, '-infinity'::timestamptz) <= now()
      order by p.scheduled_at, t2.created_at
      limit greatest(p_limit, 1)
      for update of t2 skip locked)
  returning t.*;
end;
$$;

-- First caller becomes the owner. Returns false if the workspace is taken.
create function public.claim_owner(p_user uuid, p_email text) returns boolean
  language plpgsql security definer set search_path = ''
as $$
begin
  perform pg_advisory_xact_lock(hashtext('social_scheduler_claim_owner'));
  if exists (select 1 from public.members) then
    return false;
  end if;
  insert into public.members (user_id, role, email) values (p_user, 'owner', p_email);
  return true;
end;
$$;

-- Media files that are old enough to delete.
create function public.media_to_purge(p_limit integer default 50)
  returns table (media_id uuid, storage_path text)
  language sql stable security definer set search_path = ''
as $$
  select m.id, m.storage_path
    from public.post_media m
    join public.posts p on p.id = m.post_id
   where m.storage_path is not null
     and p.status in ('published', 'partial')
     and p.published_at < now() - make_interval(days => (select media_retention_days from public.app_config where id))
   order by p.published_at
   limit greatest(p_limit, 1);
$$;

revoke all on function public.claim_due_targets(integer) from public, anon, authenticated;
revoke all on function public.claim_owner(uuid, text) from public, anon, authenticated;
revoke all on function public.media_to_purge(integer) from public, anon, authenticated;
grant execute on function public.claim_due_targets(integer) to service_role;
grant execute on function public.claim_owner(uuid, text) to service_role;
grant execute on function public.media_to_purge(integer) to service_role;

-- ---------------------------------------------------------------------------
-- Sign-ups are closed: accounts are only created through the Edge Function
-- ---------------------------------------------------------------------------
create function private.guard_signups() returns trigger
  language plpgsql security definer set search_path = ''
as $$
begin
  if exists (select 1 from public.app_config c where c.id and c.signup_window_until > now()) then
    return new;
  end if;
  raise exception 'Sign-ups are closed for this app.';
end;
$$;

create trigger social_scheduler_guard_signups
  before insert on auth.users
  for each row execute function private.guard_signups();

-- ---------------------------------------------------------------------------
-- Media storage (private bucket; the worker hands platforms short-lived links)
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public)
values ('media', 'media', false)
on conflict (id) do nothing;

create policy media_select on storage.objects
  for select to authenticated using (bucket_id = 'media' and (select private.is_member()));
create policy media_insert on storage.objects
  for insert to authenticated with check (bucket_id = 'media' and (select private.is_member()));
create policy media_update on storage.objects
  for update to authenticated
  using (bucket_id = 'media' and (select private.is_member()))
  with check (bucket_id = 'media' and (select private.is_member()));
create policy media_delete on storage.objects
  for delete to authenticated using (bucket_id = 'media' and (select private.is_member()));

-- ---------------------------------------------------------------------------
-- Explicit grants (do not rely on the project's default privileges)
-- ---------------------------------------------------------------------------
grant all on all tables in schema public to service_role;

grant select on public.members to authenticated;
grant select, delete on public.social_accounts to authenticated;
grant select, insert, update, delete
  on public.posts, public.post_media, public.post_targets, public.snippets, public.posting_slots
  to authenticated;

-- Secrets: nobody but the service role, whatever the defaults are.
revoke all on public.app_config, public.platform_credentials, public.social_account_tokens, public.oauth_states
  from anon, authenticated;
revoke insert, update on public.social_accounts from anon, authenticated;
revoke all on public.members, public.social_accounts, public.posts, public.post_media, public.post_targets,
  public.snippets, public.posting_slots from anon;
revoke insert, update, delete on public.members from authenticated;
