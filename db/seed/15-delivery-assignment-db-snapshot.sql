select now() as snapshot_at;

select
    state,
    count(*) as connection_count
from pg_stat_activity
where datname = 'hublink'
group by state
order by connection_count desc, state;

select
    wait_event_type,
    wait_event,
    count(*) as wait_count
from pg_stat_activity
where datname = 'hublink'
  and wait_event is not null
group by wait_event_type, wait_event
order by wait_count desc, wait_event_type, wait_event;

select
    pid,
    usename,
    application_name,
    state,
    wait_event_type,
    wait_event,
    now() - query_start as query_age,
    left(query, 180) as query_preview
from pg_stat_activity
where datname = 'hublink'
  and pid <> pg_backend_pid()
  and state <> 'idle'
order by query_start asc
limit 20;

select
    mode,
    count(*) as lock_count
from pg_locks
where database = (select oid from pg_database where datname = 'hublink')
group by mode
order by lock_count desc, mode;

select
    count(*) as total_deliveries,
    count(*) filter (
        where deleted_at is null
          and status not in ('DELIVERED', 'CANCELLED')
    ) as active_deliveries
from delivery_service.p_deliveries;

select
    count(*) as total_route_histories,
    count(*) filter (
        where deleted_at is null
          and status not in ('COMPLETED', 'SKIPPED', 'FAILED')
    ) as active_route_histories
from delivery_service.p_delivery_route_histories;

select
    relname,
    n_live_tup,
    n_dead_tup
from pg_stat_user_tables
where schemaname = 'delivery_service'
  and relname in (
      'p_deliveries',
      'p_delivery_route_histories',
      'p_delivery_outboxes'
  )
order by relname;
