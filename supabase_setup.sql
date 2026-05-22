-- Run this once in Supabase: SQL Editor -> New query -> paste -> Run.

create table if not exists public.health_daily (
    day          date primary key,
    steps        integer,
    distance_km  numeric,
    calories     integer,
    updated_at   timestamptz default now()
);

alter table public.health_daily enable row level security;

drop policy if exists "anon all health_daily" on public.health_daily;
create policy "anon all health_daily"
    on public.health_daily
    for all
    to anon
    using (true)
    with check (true);

grant usage on schema public to anon;
grant select, insert, update on public.health_daily to anon;
