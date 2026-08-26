create table if not exists public.tessellate_compatibility_reports (
    event_id uuid primary key,
    received_at timestamptz not null default now(),
    tessellate_version text not null,
    minecraft_version text not null,
    loader text not null,
    component text not null,
    event_kind text not null,
    fallback_reason_code text,
    failure_class text,
    entity_type_id text,
    block_entity_type_id text,
    suspected_mod_id text,
    suspected_mod_version text,
    suspected_frame text,
    loaded_mods jsonb not null,
    constraint compatibility_loader check (loader in ('fabric', 'neoforge')),
    constraint compatibility_mods_array check (
        case when jsonb_typeof(loaded_mods) = 'array'
            then jsonb_array_length(loaded_mods) <= 1000
                and pg_column_size(loaded_mods) <= 262144
            else false
        end
    ),
    constraint compatibility_payload_bounds check (
        length(tessellate_version) <= 64
        and length(minecraft_version) <= 64
        and length(component) <= 64
        and length(event_kind) <= 64
        and (fallback_reason_code is null
            or fallback_reason_code ~ '^[a-z][a-z0-9_]{0,63}$')
        and coalesce(length(failure_class), 0) <= 256
        and coalesce(length(entity_type_id), 0) <= 256
        and coalesce(length(block_entity_type_id), 0) <= 256
        and coalesce(length(suspected_mod_id), 0) <= 64
        and coalesce(length(suspected_mod_version), 0) <= 128
        and coalesce(length(suspected_frame), 0) <= 512
    )
);

alter table public.tessellate_compatibility_reports
    add column if not exists entity_type_id text;

alter table public.tessellate_compatibility_reports
    add column if not exists block_entity_type_id text;

alter table public.tessellate_compatibility_reports
    add column if not exists fallback_reason_code text;

alter table public.tessellate_compatibility_reports
    drop constraint if exists compatibility_payload_bounds;
alter table public.tessellate_compatibility_reports
    add constraint compatibility_payload_bounds check (
        length(tessellate_version) <= 64
        and length(minecraft_version) <= 64
        and length(component) <= 64
        and length(event_kind) <= 64
        and (fallback_reason_code is null
            or fallback_reason_code ~ '^[a-z][a-z0-9_]{0,63}$')
        and coalesce(length(failure_class), 0) <= 256
        and coalesce(length(entity_type_id), 0) <= 256
        and coalesce(length(block_entity_type_id), 0) <= 256
        and coalesce(length(suspected_mod_id), 0) <= 64
        and coalesce(length(suspected_mod_version), 0) <= 128
        and coalesce(length(suspected_frame), 0) <= 512
    );

create index if not exists tessellate_reports_suspected_mod
    on public.tessellate_compatibility_reports (suspected_mod_id, received_at desc);

create index if not exists tessellate_reports_entity_candidates
    on public.tessellate_compatibility_reports (
        suspected_mod_id, suspected_mod_version, loader, entity_type_id, received_at desc
    )
    where suspected_mod_id is not null and entity_type_id is not null;

create index if not exists tessellate_reports_mod_candidates
    on public.tessellate_compatibility_reports (
        suspected_mod_id, suspected_mod_version, loader, component,
        block_entity_type_id, received_at desc
    )
    where suspected_mod_id is not null and entity_type_id is null;

alter table public.tessellate_compatibility_reports enable row level security;
alter table public.tessellate_compatibility_reports force row level security;
revoke all on public.tessellate_compatibility_reports from public, anon, authenticated;

drop policy if exists "accept compatibility reports" on public.tessellate_compatibility_reports;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create table if not exists private.tessellate_report_rate_limits (
    bucket text not null,
    window_started_at timestamptz not null,
    request_count integer not null,
    primary key (bucket, window_started_at),
    constraint tessellate_report_rate_bucket check (length(bucket) between 1 and 64),
    constraint tessellate_report_rate_count check (request_count between 1 and 5000)
);

create index if not exists tessellate_report_rate_window
    on private.tessellate_report_rate_limits (window_started_at);

alter table private.tessellate_report_rate_limits enable row level security;
alter table private.tessellate_report_rate_limits force row level security;
revoke all on private.tessellate_report_rate_limits from public, anon, authenticated;
grant select, insert, update, delete on private.tessellate_report_rate_limits to service_role;
grant insert on public.tessellate_compatibility_reports to service_role;

create or replace function public.submit_tessellate_compatibility_report(
    p_bucket text,
    p_report jsonb
) returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
    current_window timestamptz := date_trunc('hour', now());
    accepted_count integer;
begin
    if p_bucket !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid_rate_limit_bucket' using errcode = '22023';
    end if;
    if jsonb_typeof(p_report) <> 'object' then
        raise exception 'invalid_report' using errcode = '22023';
    end if;

    insert into private.tessellate_report_rate_limits (
        bucket, window_started_at, request_count
    ) values (p_bucket, current_window, 1)
    on conflict (bucket, window_started_at) do update
        set request_count = private.tessellate_report_rate_limits.request_count + 1
        where private.tessellate_report_rate_limits.request_count < 60
    returning request_count into accepted_count;
    if accepted_count is null then
        raise exception 'rate_limit_exceeded' using errcode = 'P0001';
    end if;

    accepted_count := null;
    insert into private.tessellate_report_rate_limits (
        bucket, window_started_at, request_count
    ) values ('global', current_window, 1)
    on conflict (bucket, window_started_at) do update
        set request_count = private.tessellate_report_rate_limits.request_count + 1
        where private.tessellate_report_rate_limits.request_count < 5000
    returning request_count into accepted_count;
    if accepted_count is null then
        raise exception 'rate_limit_exceeded' using errcode = 'P0001';
    end if;

    insert into public.tessellate_compatibility_reports (
        event_id, tessellate_version, minecraft_version, loader, component, event_kind,
        fallback_reason_code, failure_class, entity_type_id, block_entity_type_id, suspected_mod_id,
        suspected_mod_version, suspected_frame, loaded_mods
    ) values (
        (p_report ->> 'event_id')::uuid,
        p_report ->> 'tessellate_version',
        p_report ->> 'minecraft_version',
        p_report ->> 'loader',
        p_report ->> 'component',
        p_report ->> 'event_kind',
        p_report ->> 'fallback_reason_code',
        p_report ->> 'failure_class',
        p_report ->> 'entity_type_id',
        p_report ->> 'block_entity_type_id',
        p_report ->> 'suspected_mod_id',
        p_report ->> 'suspected_mod_version',
        p_report ->> 'suspected_frame',
        p_report -> 'loaded_mods'
    );

    if random() < 0.01 then
        delete from private.tessellate_report_rate_limits
        where window_started_at < current_window - interval '24 hours';
    end if;
end;
$$;

revoke all on function public.submit_tessellate_compatibility_report(text, jsonb)
    from public, anon, authenticated;
grant execute on function public.submit_tessellate_compatibility_report(text, jsonb)
    to service_role;

create table if not exists public.tessellate_entity_compatibility_rules (
    mod_id text not null,
    mod_version text not null default '*',
    loader text not null default 'any',
    entity_type_id text not null,
    reason text,
    enabled boolean not null default true,
    updated_at timestamptz not null default now(),
    primary key (mod_id, mod_version, loader, entity_type_id),
    constraint compatibility_rule_mod_id check (
        mod_id ~ '^[a-z][a-z0-9_]{1,63}$'
    ),
    constraint compatibility_rule_mod_version check (
        length(mod_version) between 1 and 128
    ),
    constraint compatibility_rule_loader check (
        loader in ('any', 'fabric', 'neoforge')
    ),
    constraint compatibility_rule_entity_type check (
        length(entity_type_id) <= 256
        and entity_type_id ~ '^[a-z0-9_.-]+:[a-z0-9_./-]+$'
    ),
    constraint compatibility_rule_reason check (
        coalesce(length(reason), 0) <= 512
    )
);

create index if not exists tessellate_entity_rules_mod
    on public.tessellate_entity_compatibility_rules (mod_id, enabled);

alter table public.tessellate_entity_compatibility_rules enable row level security;
alter table public.tessellate_entity_compatibility_rules force row level security;
revoke all on public.tessellate_entity_compatibility_rules
    from public, anon, authenticated;
grant select on public.tessellate_entity_compatibility_rules to anon;

drop policy if exists "read active entity compatibility rules"
    on public.tessellate_entity_compatibility_rules;
create policy "read active entity compatibility rules"
    on public.tessellate_entity_compatibility_rules
    for select
    to anon
    using (enabled);

create table if not exists public.tessellate_mod_compatibility_rules (
    mod_id text not null,
    mod_version text not null default '*',
    loader text not null default 'any',
    action text not null,
    target_id text not null default '',
    reason text,
    enabled boolean not null default true,
    updated_at timestamptz not null default now(),
    primary key (mod_id, mod_version, loader, action, target_id),
    constraint compatibility_mod_rule_mod_id check (
        mod_id ~ '^[a-z][a-z0-9_]{1,63}$'
    ),
    constraint compatibility_mod_rule_version check (
        length(mod_version) between 1 and 128
    ),
    constraint compatibility_mod_rule_loader check (
        loader in ('any', 'fabric', 'neoforge')
    ),
    constraint compatibility_mod_rule_action check (
        action in (
            'main_thread_block_entity',
            'serialize_entity_ticks',
            'disable_parallel_spawning',
            'force_serial_regions'
        )
    ),
    constraint compatibility_mod_rule_target check (
        (action = 'main_thread_block_entity'
            and length(target_id) <= 256
            and target_id ~ '^[a-z0-9_.-]+:[a-z0-9_./-]+$')
        or (action <> 'main_thread_block_entity' and target_id = '')
    ),
    constraint compatibility_mod_rule_reason check (
        coalesce(length(reason), 0) <= 512
    )
);

create index if not exists tessellate_mod_rules_mod
    on public.tessellate_mod_compatibility_rules (mod_id, enabled);

alter table public.tessellate_mod_compatibility_rules enable row level security;
alter table public.tessellate_mod_compatibility_rules force row level security;
revoke all on public.tessellate_mod_compatibility_rules
    from public, anon, authenticated;
grant select on public.tessellate_mod_compatibility_rules to anon;

drop policy if exists "read active mod compatibility rules"
    on public.tessellate_mod_compatibility_rules;
create policy "read active mod compatibility rules"
    on public.tessellate_mod_compatibility_rules
    for select
    to anon
    using (enabled);

create or replace view public.tessellate_entity_compatibility_candidates
with (security_invoker = true)
as
with candidates as (
    select
        suspected_mod_id as mod_id,
        suspected_mod_version as mod_version,
        loader,
        entity_type_id,
        count(*) as failure_count,
        min(received_at) as first_seen,
        max(received_at) as last_seen,
        array_agg(distinct component) as components,
        array_remove(array_agg(distinct failure_class), null) as failure_classes,
        array_agg(distinct tessellate_version) as tessellate_versions,
        (array_agg(suspected_frame order by received_at desc)
            filter (where suspected_frame is not null))[1] as sample_frame
    from public.tessellate_compatibility_reports
    where suspected_mod_id is not null and entity_type_id is not null
    group by suspected_mod_id, suspected_mod_version, loader, entity_type_id
), matching_rules as (
    select mod_id, mod_version, loader, entity_type_id, enabled,
        null::text[] as covered_components
    from public.tessellate_entity_compatibility_rules
    union all
    select mod_id, mod_version, loader, null, enabled,
        case action
            when 'force_serial_regions' then array['region-worker', 'region-ticking']
            when 'disable_parallel_spawning' then array['natural-spawning']
        end
    from public.tessellate_mod_compatibility_rules
    where action in ('force_serial_regions', 'disable_parallel_spawning')
)
select
    candidates.mod_id,
    candidates.mod_version,
    candidates.loader,
    candidates.entity_type_id,
    case
        when count(*) filter (where rules.enabled) > 0 then 'active'
        when count(rules.mod_id) > 0 then 'disabled'
        else 'unreviewed'
    end as rule_status,
    candidates.failure_count,
    candidates.first_seen,
    candidates.last_seen,
    candidates.failure_classes,
    candidates.tessellate_versions,
    candidates.sample_frame,
    array_remove(array_agg(distinct
        case when rules.mod_id is not null
            then rules.loader || '/' || rules.mod_version
            else null
        end), null) as matching_rule_scopes,
    format(
        'insert into public.tessellate_entity_compatibility_rules '
        || '(mod_id, mod_version, loader, entity_type_id, reason) '
        || 'values (%L, %L, %L, %L, %L) '
        || 'on conflict (mod_id, mod_version, loader, entity_type_id) '
        || 'do update set enabled = true, reason = excluded.reason, updated_at = now();',
        candidates.mod_id,
        coalesce(candidates.mod_version, '*'),
        candidates.loader,
        candidates.entity_type_id,
        'confirmed from compatibility reports'
    ) as promotion_sql
from candidates
left join matching_rules as rules
    on rules.mod_id = candidates.mod_id
    and (rules.entity_type_id = candidates.entity_type_id
        or (rules.entity_type_id is null
            and candidates.components <@ rules.covered_components))
    and (rules.mod_version = '*' or rules.mod_version = candidates.mod_version)
    and (rules.loader = 'any' or rules.loader = candidates.loader)
group by
    candidates.mod_id,
    candidates.mod_version,
    candidates.loader,
    candidates.entity_type_id,
    candidates.failure_count,
    candidates.first_seen,
    candidates.last_seen,
    candidates.failure_classes,
    candidates.tessellate_versions,
    candidates.sample_frame
order by candidates.failure_count desc, candidates.last_seen desc;

revoke all on public.tessellate_entity_compatibility_candidates
    from public, anon, authenticated;

comment on view public.tessellate_entity_compatibility_candidates is
    'Entity failure candidates with status from entity rules or covering subsystem rules.';

create or replace view public.tessellate_mod_compatibility_candidates
with (security_invoker = true)
as
with candidates as (
    select
        suspected_mod_id as mod_id,
        suspected_mod_version as mod_version,
        loader,
        component,
        case
            when block_entity_type_id is not null then 'main_thread_block_entity'
            when component = 'natural-spawning' then 'disable_parallel_spawning'
            else 'force_serial_regions'
        end as suggested_action,
        coalesce(block_entity_type_id, '') as target_id,
        count(*) as failure_count,
        min(received_at) as first_seen,
        max(received_at) as last_seen,
        array_remove(array_agg(distinct failure_class), null) as failure_classes,
        array_agg(distinct tessellate_version) as tessellate_versions,
        (array_agg(suspected_frame order by received_at desc)
            filter (where suspected_frame is not null))[1] as sample_frame
    from public.tessellate_compatibility_reports
    where suspected_mod_id is not null and entity_type_id is null
        and (block_entity_type_id is not null
            or component in ('region-worker', 'region-ticking', 'natural-spawning'))
    group by suspected_mod_id, suspected_mod_version, loader, component,
        block_entity_type_id
)
select
    candidates.mod_id,
    candidates.mod_version,
    candidates.loader,
    candidates.component,
    candidates.suggested_action,
    nullif(candidates.target_id, '') as target_id,
    case
        when count(*) filter (where rules.enabled) > 0 then 'active'
        when count(rules.mod_id) > 0 then 'disabled'
        else 'unreviewed'
    end as rule_status,
    candidates.failure_count,
    candidates.first_seen,
    candidates.last_seen,
    candidates.failure_classes,
    candidates.tessellate_versions,
    candidates.sample_frame,
    array_remove(array_agg(distinct
        case when rules.mod_id is not null
            then rules.loader || '/' || rules.mod_version
            else null
        end), null) as matching_rule_scopes,
    format(
        'insert into public.tessellate_mod_compatibility_rules '
        || '(mod_id, mod_version, loader, action, target_id, reason) '
        || 'values (%L, %L, %L, %L, %L, %L) '
        || 'on conflict (mod_id, mod_version, loader, action, target_id) '
        || 'do update set enabled = true, reason = excluded.reason, updated_at = now();',
        candidates.mod_id,
        coalesce(candidates.mod_version, '*'),
        candidates.loader,
        candidates.suggested_action,
        candidates.target_id,
        'confirmed from compatibility reports: ' || candidates.component
    ) as promotion_sql
from candidates
left join public.tessellate_mod_compatibility_rules as rules
    on rules.mod_id = candidates.mod_id
    and rules.action = candidates.suggested_action
    and rules.target_id = candidates.target_id
    and (rules.mod_version = '*' or rules.mod_version = candidates.mod_version)
    and (rules.loader = 'any' or rules.loader = candidates.loader)
group by
    candidates.mod_id,
    candidates.mod_version,
    candidates.loader,
    candidates.component,
    candidates.suggested_action,
    candidates.target_id,
    candidates.failure_count,
    candidates.first_seen,
    candidates.last_seen,
    candidates.failure_classes,
    candidates.tessellate_versions,
    candidates.sample_frame
order by candidates.failure_count desc, candidates.last_seen desc;

revoke all on public.tessellate_mod_compatibility_candidates
    from public, anon, authenticated;

comment on view public.tessellate_mod_compatibility_candidates is
    'Block-entity and subsystem failure candidates with curated compatibility rule status.';

-- Only the Edge Function's service role can submit reports. Public clients can read enabled rules
-- but cannot read reports, call the ingestion RPC, or write compatibility rules.
-- Reports remain untrusted evidence; never promote them automatically.
