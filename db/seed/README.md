# HubLink Seed Files

This directory contains SQL seed files and sample payloads for scenario runs, load-test baselines, and query experiments.

## Files

- `00-reset-scenarios.sql`: reset orders, deliveries, outbox, stock history, and restore stock quantity
- `01-base-scenarios.sql`: base hubs, companies, products, stock, users, and delivery managers
- `10-reset-delivery-loadtest.sql`: clear delivery load-test runtime tables
- `11-reset-delivery-loadtest-baseline.sql`: restore the lock-test baseline for delivery creation
- `12-reset-delivery-query-baseline.sql`: restore the large-row query baseline for delivery experiments
- `13-explain-delivery-query-baseline.sql`: `EXPLAIN ANALYZE` set for delivery query experiments
- `14-reset-delivery-perf-baseline.sql`: restore the shared performance baseline for delivery lock and query experiments
- `15-delivery-assignment-db-snapshot.sql`: capture PostgreSQL connection, wait, lock, and active-row snapshots during assignment tests
- `orders/01-success-order.json`: normal order scenario
- `orders/02-stock-fail-order.json`: stock shortage scenario
- `orders/03-delivery-fail-order.json`: delivery failure and stock compensation scenario

## When To Use

- Lock contention, active assignment, connection pool, and response-time tests:
  - `11-reset-delivery-loadtest-baseline.sql`
- Large-row query, index, and outbox polling experiments:
  - `12-reset-delivery-query-baseline.sql`
  - `13-explain-delivery-query-baseline.sql`
- Shared delivery performance baseline for load, lock contention, and query tuning comparison:
  - `14-reset-delivery-perf-baseline.sql`
- PostgreSQL internal snapshots during delivery assignment tests:
  - `15-delivery-assignment-db-snapshot.sql`

## Basic Flow

Run the base scenario after the services have created the tables.

```powershell
$project = "hublink-503802"
$zone = "asia-northeast3-a"

gcloud compute scp db/seed/01-base-scenarios.sql hublink-data-vm:/tmp/01-base-scenarios.sql --zone $zone --project $project
gcloud compute ssh hublink-data-vm --zone $zone --project $project --command "sudo docker exec -i hublink-postgres psql -U hublink -d hublink < /tmp/01-base-scenarios.sql"
```

Use the reset scenario before rerunning from a clean state.

```powershell
gcloud compute scp db/seed/00-reset-scenarios.sql hublink-data-vm:/tmp/00-reset-scenarios.sql --zone $zone --project $project
gcloud compute ssh hublink-data-vm --zone $zone --project $project --command "sudo docker exec -i hublink-postgres psql -U hublink -d hublink < /tmp/00-reset-scenarios.sql"
```
