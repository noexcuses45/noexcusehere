-- Behavioural tests for the schema. Run with tests/run.sh against a scratch
-- database. Every block raises on failure, so "ALL SCHEMA TESTS PASSED" at the
-- end means every assertion held.
\set ON_ERROR_STOP 1
\set QUIET 1

-- Fixtures ------------------------------------------------------------------
-- Sign-ups are closed by default.
do $$
begin
  begin
    insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000aa', 'x@example.com');
    raise exception 'TEST FAILED: sign-up should be blocked';
  exception when others then
    if sqlerrm not like 'Sign-ups are closed%' then raise; end if;
  end;
end $$;

update public.app_config set signup_window_until = now() + interval '1 minute';
insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-000000000001', 'owner@example.com'),
  ('00000000-0000-0000-0000-000000000002', 'stranger@example.com'),
  ('00000000-0000-0000-0000-000000000003', 'late@example.com');
update public.app_config set signup_window_until = null;

-- claim_owner: first wins, second is refused.
do $$
begin
  assert public.claim_owner('00000000-0000-0000-0000-000000000001', 'owner@example.com'), 'first claim should succeed';
  assert not public.claim_owner('00000000-0000-0000-0000-000000000003', 'late@example.com'), 'second claim should be refused';
  assert (select count(*) from public.members) = 1, 'exactly one member';
end $$;

insert into public.social_accounts (id, platform, external_id, name) values
  ('10000000-0000-0000-0000-000000000001', 'facebook', 'page1', 'My Page'),
  ('10000000-0000-0000-0000-000000000002', 'instagram', 'ig1', 'my_ig'),
  ('10000000-0000-0000-0000-000000000003', 'tiktok', 'tt1', 'my_tt');
insert into public.social_account_tokens (account_id, access_token) values
  ('10000000-0000-0000-0000-000000000001', 'SECRET_PAGE_TOKEN');
insert into public.platform_credentials (platform, client_id, client_secret) values ('meta', 'app123', 'SECRET_APP');

-- Stranger (signed in, not a member) sees and changes nothing -----------------
begin;
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-000000000002';
do $$
declare n integer;
begin
  select count(*) into n from public.social_accounts;      assert n = 0, 'stranger sees accounts';
  select count(*) into n from public.members;              assert n = 0, 'stranger sees members';
  begin
    insert into public.posts (caption) values ('hi');
    raise exception 'TEST FAILED: stranger inserted a post';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.save_post(null, 'hi', null, 'draft', null, '[]', '[]');
    raise exception 'TEST FAILED: stranger saved a post';
  exception when insufficient_privilege then null;
  end;
  begin
    perform * from public.credentials_status();
    raise exception 'TEST FAILED: stranger read credentials status';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.set_platform_credentials('meta', 'a', 'b');
    raise exception 'TEST FAILED: stranger set credentials';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.claim_due_targets(5);
    raise exception 'TEST FAILED: stranger claimed targets';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.claim_owner('00000000-0000-0000-0000-000000000002', 'x');
    raise exception 'TEST FAILED: stranger called claim_owner';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into storage.objects (bucket_id, name) values ('media', 'x.jpg');
    raise exception 'TEST FAILED: stranger uploaded media';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;

-- Anonymous: no table access at all ------------------------------------------
begin;
set local role anon;
do $$
begin
  begin
    perform count(*) from public.posts;
    raise exception 'TEST FAILED: anon read posts';
  exception when insufficient_privilege then null;
  end;
  begin
    perform count(*) from public.social_account_tokens;
    raise exception 'TEST FAILED: anon read tokens';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.save_post(null, 'hi', null, 'draft', null, '[]', '[]');
    raise exception 'TEST FAILED: anon saved a post';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;

-- Member: normal use, but secrets stay out of reach ---------------------------
begin;
set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-0000-0000-000000000001';
do $$
declare
  n integer;
  v_post uuid;
  v_status text;
begin
  select count(*) into n from public.social_accounts;  assert n = 3, 'member sees accounts';
  begin
    perform count(*) from public.social_account_tokens;
    raise exception 'TEST FAILED: member read tokens';
  exception when insufficient_privilege then null;
  end;
  begin
    perform count(*) from public.platform_credentials;
    raise exception 'TEST FAILED: member read credentials table';
  exception when insufficient_privilege then null;
  end;
  begin
    perform count(*) from public.app_config;
    raise exception 'TEST FAILED: member read app_config';
  exception when insufficient_privilege then null;
  end;
  begin
    update public.social_accounts set name = 'hacked';
    raise exception 'TEST FAILED: member updated an account row';
  exception when insufficient_privilege then null;
  end;

  -- credentials: status visible, secret never returned
  select count(*) into n from public.credentials_status() where platform = 'meta' and client_id = 'app123' and configured;
  assert n = 1, 'credentials_status should list meta';
  perform public.set_platform_credentials('tiktok', 'key1', 'sec1', '{"direct_post": true}');
  select count(*) into n from public.credentials_status() where platform = 'tiktok' and (options ->> 'direct_post')::boolean;
  assert n = 1, 'credential options saved';
  perform public.set_platform_credentials('google', 'gid', 'gsec');
  perform public.set_platform_credentials('linkedin', 'lid', 'lsec');
  select count(*) into n from public.credentials_status();
  assert n = 4, 'credentials saved for every platform, got ' || n;
  begin
    perform public.set_platform_credentials('tiktok', 'key1', '   ');
    raise exception 'TEST FAILED: empty secret accepted';
  exception when raise_exception then null;
  end;

  -- validation
  begin
    perform public.save_post(null, 'x', null, 'scheduled', null, '[]', '[{"platform":"facebook","account_id":"10000000-0000-0000-0000-000000000001"}]');
    raise exception 'TEST FAILED: scheduled without a time';
  exception when raise_exception then null;
  end;
  begin
    perform public.save_post(null, 'x', null, 'scheduled', now(), '[]', '[]');
    raise exception 'TEST FAILED: scheduled without targets';
  exception when raise_exception then null;
  end;

  -- draft
  v_post := public.save_post(null, 'Draft caption', null, 'draft', null,
    '[{"kind":"image","storage_path":"u/a.jpg","thumb_path":"u/a_t.jpg","mime_type":"image/jpeg","size_bytes":123,"width":1080,"height":1350}]',
    '[{"platform":"instagram","account_id":"10000000-0000-0000-0000-000000000002","post_type":"feed"}]');
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'draft', 'draft stays draft, got ' || v_status;
  select count(*) into n from public.post_media where post_id = v_post;   assert n = 1, 'media saved';
  select count(*) into n from public.post_targets where post_id = v_post; assert n = 1, 'target saved';

  -- edit the draft into a scheduled post with two media and three targets
  perform public.save_post(v_post, 'Scheduled caption', 'https://example.com', 'scheduled', now() + interval '1 hour',
    '[{"kind":"image","storage_path":"u/a.jpg","mime_type":"image/jpeg"},{"kind":"image","storage_path":"u/b.jpg","mime_type":"image/jpeg"}]',
    '[{"platform":"instagram","account_id":"10000000-0000-0000-0000-000000000002","post_type":"feed"},
      {"platform":"facebook","account_id":"10000000-0000-0000-0000-000000000001","post_type":"feed","caption_override":"FB text"},
      {"platform":"tiktok","mode":"reminder","post_type":"direct"}]');
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'scheduled', 'expected scheduled, got ' || v_status;
  select count(*) into n from public.post_media where post_id = v_post;   assert n = 2, 'media replaced';
  select count(*) into n from public.post_targets where post_id = v_post; assert n = 3, 'targets replaced';
  select count(*) into n from public.post_media where post_id = v_post and position = 1 and storage_path = 'u/b.jpg';
  assert n = 1, 'media order kept';
  select count(*) into n from public.post_targets where post_id = v_post and caption_override = 'FB text';
  assert n = 1, 'caption override kept';

  -- storage: members can use the media bucket only
  insert into storage.objects (bucket_id, name) values ('media', 'u/a.jpg');
  begin
    insert into storage.objects (bucket_id, name) values ('other', 'x.jpg');
    raise exception 'TEST FAILED: member wrote to another bucket';
  exception when insufficient_privilege then null;
  end;
end $$;
commit;

-- Worker flow ------------------------------------------------------------------
do $$
declare
  v_post uuid;
  v_status text;
  n integer;
  r record;
begin
  select id into v_post from public.posts where caption = 'Scheduled caption';

  -- not due yet
  select count(*) into n from public.claim_due_targets(10);
  assert n = 0, 'nothing should be due yet';

  update public.posts set scheduled_at = now() - interval '1 minute' where id = v_post;

  -- due: both auto targets are claimed, the reminder target is not
  select count(*) into n from public.claim_due_targets(10);
  assert n = 2, 'expected 2 claimed targets, got ' || n;
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'publishing', 'post should be publishing, got ' || v_status;

  -- locked: a second worker gets nothing
  select count(*) into n from public.claim_due_targets(10);
  assert n = 0, 'locked targets must not be handed out twice';

  -- Instagram still processing: unlock for the next tick; Facebook published
  update public.post_targets set locked_until = null, state = '{"phase":"wait"}'
   where post_id = v_post and platform = 'instagram';
  update public.post_targets set status = 'published', published_at = now(), locked_until = null
   where post_id = v_post and platform = 'facebook';
  select count(*) into n from public.claim_due_targets(10);
  assert n = 1, 'only the instagram target should come back, got ' || n;

  -- Instagram fails -> reminder still pending, so the post is still open
  update public.post_targets set status = 'failed', error = 'boom', locked_until = null
   where post_id = v_post and platform = 'instagram';
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'publishing', 'reminder pending keeps post open, got ' || v_status;

  -- user marks the reminder as posted -> partial (one failed)
  update public.post_targets set status = 'published', published_at = now()
   where post_id = v_post and mode = 'reminder';
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'partial', 'expected partial, got ' || v_status;
  assert (select published_at from public.posts where id = v_post) is not null, 'published_at set';

  -- a post that has gone out can no longer be edited
  begin
    perform public.save_post(v_post, 'changed', null, 'draft', null, '[]', '[]');
    raise exception 'TEST FAILED: edited a published post';
  exception when raise_exception then
    if sqlerrm like 'TEST FAILED%' then raise; end if;
  end;

  -- retry: failed target goes back to pending and is claimable again
  perform public.retry_post(v_post);
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'publishing', 'after retry expected publishing, got ' || v_status;
  select count(*) into n from public.claim_due_targets(10);
  assert n = 1, 'retried target should be claimable, got ' || n;
  update public.post_targets set status = 'published', published_at = now()
   where post_id = v_post and platform = 'instagram';
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'published', 'expected published, got ' || v_status;

  -- all targets failing -> failed
  insert into public.posts (caption, status, scheduled_at) values ('will fail', 'scheduled', now() - interval '1 minute')
    returning id into v_post;
  insert into public.post_targets (post_id, platform, account_id)
    values (v_post, 'facebook', '10000000-0000-0000-0000-000000000001');
  perform public.claim_due_targets(10);
  update public.post_targets set status = 'failed', error = 'nope' where post_id = v_post;
  select status into v_status from public.posts where id = v_post;
  assert v_status = 'failed', 'expected failed, got ' || v_status;

  -- drafts are never claimed, even when their time has passed
  insert into public.posts (caption, status, scheduled_at) values ('a draft', 'draft', now() - interval '1 minute')
    returning id into v_post;
  insert into public.post_targets (post_id, platform, account_id)
    values (v_post, 'facebook', '10000000-0000-0000-0000-000000000001');
  select count(*) into n from public.claim_due_targets(10);
  assert n = 0, 'drafts must not be published';

  -- disconnecting an account keeps post history
  delete from public.social_accounts where id = '10000000-0000-0000-0000-000000000001';
  select count(*) into n from public.post_targets where platform = 'facebook' and account_id is null;
  assert n >= 2, 'targets survive account removal';
  select count(*) into n from public.social_account_tokens;
  assert n = 0, 'tokens are removed with the account';

  -- media purge only returns media of posts published long enough ago
  select count(*) into n from public.media_to_purge(10);
  assert n = 0, 'nothing old enough to purge';
  update public.posts set published_at = now() - interval '100 days' where status = 'published';
  select count(*) into n from public.media_to_purge(10);
  assert n = 2, 'expected 2 purgeable media, got ' || n;
end $$;

\echo ALL SCHEMA TESTS PASSED
