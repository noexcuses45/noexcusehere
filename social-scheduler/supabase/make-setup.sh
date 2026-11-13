#!/usr/bin/env bash
# Rebuilds setup.sql (the paste-once file for the Supabase SQL editor) from
# the migrations. Run after changing a migration; CI checks it is up to date.
set -euo pipefail
cd "$(dirname "$0")"
{
cat <<'HEADER'
-- Social Scheduler: one-shot database setup.
--
-- GENERATED from migrations/0001_init.sql and migrations/0002_cron.sql, plus
-- the line that points the every-minute job at this project's function.
-- Paste the whole file into the Supabase SQL editor and press Run, once.
-- (If you move to another Supabase project, change the address on the last line.)

HEADER
cat migrations/0001_init.sql
echo
cat migrations/0002_cron.sql
cat <<'FOOTER'

-- Point the every-minute job at the deployed function.
update public.app_config
   set function_url = 'https://zmspizvogocvebmoctbo.supabase.co/functions/v1/scheduler',
       updated_at = now();
FOOTER
} > setup.sql
