#!/usr/bin/env bash
# Applies the schema to a scratch database on a local Postgres and runs the
# behavioural tests.  Usage: PGHOST=... PGPORT=... PGUSER=postgres tests/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."
DB=sched_test_$$
PSQL="psql -v ON_ERROR_STOP=1 -q"
$PSQL -d postgres -c "create database $DB"
trap '$PSQL -d postgres -c "drop database if exists $DB" >/dev/null; for r in anon authenticated service_role; do $PSQL -d postgres -c "drop role if exists $r" >/dev/null 2>&1 || true; done' EXIT
for r in anon authenticated service_role; do $PSQL -d postgres -c "drop role if exists $r" >/dev/null 2>&1 || true; done
$PSQL -d "$DB" -f tests/supabase_stub.sql
$PSQL -d "$DB" -f migrations/0001_init.sql
$PSQL -d "$DB" -f tests/schema_test.sql
