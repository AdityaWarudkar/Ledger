# Load-test results

Measured on 22 September 2026, using k6 1.6.1 against the real HTTP service and
PostgreSQL. Both runs passed every threshold and every ledger check.

| Scenario | Virtual users | Logical transfers | Transfer HTTP requests | Logical transfers/s | p95 request latency | p99 request latency |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Independent pair per virtual user | 10 | 5,000 | 10,000 | 345.49 | 40.30 ms | 67.46 ms |
| One shared account pair | 10 | 5,000 | 10,000 | 241.33 | 68.41 ms | 92.32 ms |

Raw results: [independent pairs](../load/results/independent-pairs.json),
[shared pair](../load/results/shared-pair.json), and
[warmup](../load/results/warmup.json).

## What was measured

Each virtual user performed 500 logical transfers of one minor unit, alternating
direction. Every iteration sent **two concurrent HTTP requests with the same key
and payload**. Both had to return 201 and the same transfer ID; exactly one had
to carry the replay header. Keys were distinct across iterations and runs.

The custom latency distribution contains only those 10,000 transfer requests,
including both originals and replays. Account creation, initial funding, and
verification requests are excluded from latency. Logical throughput is the
verified transfer count divided by k6's entire run duration, including setup
and teardown: 14.47 seconds and 20.72 seconds respectively. The underlying
scenario durations were approximately 13.6 and 20.6 seconds. This conservative
rate is not a count of distinct balance changes per HTTP request.

After traffic ended, the script checked the exact final balance of every test
account, the expected number of transfers and recipient entries, and global
reconciliation. Independent pairs passed 15,102 checks; the shared pair passed
15,012. No HTTP failures, dropped iterations, unexpected replays, or invariant
failures occurred. Thresholds required all checks to pass, zero invariant
failures, p95 below one second, and p99 below two seconds.

The raw `invariant_failures` metric is a k6 Rate of boolean failure observations.
Its `rate: 0` means no failures; k6's generic `fails` field counts the false
observations in this deliberately inverted metric. The separate `checks`
metric has `fails: 0` in both runs.

## Environment and limits

- Windows 11, Intel Core i5-1240P, 16 logical CPUs, 8 GiB host RAM.
- Docker Desktop 29.8.0, Linux engine reporting 16 CPUs and 3,951,116,288 bytes
  (about 3.68 GiB) memory. No explicit per-container CPU quotas.
- Java 21, Spring Boot 3.5.16, PostgreSQL 17, default ten-connection Hikari pool.
- An isolated database on `tmpfs`; no published load-test ports. The benchmark
  does not share the demo database. The standard demo database uses a persistent
  Docker volume. The memory-backed load database can make disk writes faster.
- One 100-transfer warmup preceded the measurements. Each workload ran once.
  The database and JVM remained warm for the second workload.
- Outbox writes were enabled, but no endpoints were registered and the worker
  was disabled. These numbers measure transfers, idempotency, and outbox writes;
  they do not measure webhook delivery capacity.

These are short, closed-loop laptop benchmarks, not sustained capacity claims
or production SLO evidence. The client slows down as responses slow down, so an
arrival-rate test would be needed to study overload. Shared-pair contention is
expected to reduce throughput because writers lock the same two accounts. The
results support that behavior, but do not isolate it from all machine noise.
Longer runs, disk-backed storage, an arrival-rate workload, realistic webhook
traffic, and a controlled host would be the next performance study.

## Reproduce

Run from the repository root with Docker Desktop running:

```sh
docker compose -f compose.load.yaml up --build -d backend
docker compose -f compose.load.yaml --profile tools run --rm -e VUS=5 -e ITERATIONS=20 -e RESULT_FILE=warmup.json k6
docker compose -f compose.load.yaml --profile tools run --rm -e VUS=10 -e ITERATIONS=500 -e RESULT_FILE=independent-pairs.json k6
docker compose -f compose.load.yaml --profile tools run --rm -e VUS=10 -e ITERATIONS=500 -e SHARED_PAIR=true -e RESULT_FILE=shared-pair.json k6
docker compose -f compose.load.yaml down
```

Use `compose.load.yaml` on its own, not as an override of the demo Compose file.
The project name is `ledger-load`; its temporary database disappears when it
stops. The demo's `ledger_data` volume is unaffected. Results are written to
`load/results` and the command exits nonzero if a threshold fails. Repeated runs
replace those named result files; preserve a copy if comparing different changes.
The seeded Northstar account provides benchmark funding. Restart the isolated
load stack before a large sequence of runs exhausts that seed.

The script is [load/transfers.js](../load/transfers.js). Its thresholds and JSON
summary use the official [k6 threshold](https://grafana.com/docs/k6/latest/using-k6/thresholds/)
and [custom summary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)
APIs.
