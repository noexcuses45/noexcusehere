-- Runs the publisher every minute, but only calls the Edge Function when a
-- post is actually due (plus twice an hour for housekeeping: token refresh
-- and media clean-up).
--
-- After applying, point the job at your deployed function once:
--   update public.app_config
--      set function_url = 'https://<project-ref>.supabase.co/functions/v1/scheduler';

create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net with schema extensions;

create function private.invoke_publisher() returns void
  language plpgsql security definer set search_path = ''
as $$
declare
  cfg record;
  due boolean;
begin
  select function_url, cron_secret into cfg from public.app_config where id;
  if cfg.function_url is null then
    return;
  end if;

  select exists (
    select 1
      from public.post_targets t
      join public.posts p on p.id = t.post_id
     where t.mode = 'auto'
       and t.status in ('pending', 'processing')
       and p.status in ('scheduled', 'publishing')
       and p.scheduled_at <= now()
  ) into due;

  if not due and extract(minute from now())::integer % 30 <> 0 then
    return;
  end if;

  perform net.http_post(
    url := cfg.function_url || '/publish-due',
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-cron-secret', cfg.cron_secret),
    body := '{}'::jsonb,
    timeout_milliseconds := 10000
  );

  delete from public.oauth_states where expires_at < now();
end;
$$;

revoke all on function private.invoke_publisher() from public;

select cron.schedule('social-scheduler-publish', '* * * * *', $$select private.invoke_publisher()$$);
