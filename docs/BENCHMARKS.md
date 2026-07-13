# Benchmark Methodology

This document explains *how* every performance number for the system is produced,
so each figure can be traced to a repeatable measurement rather than an estimate.
The scripts live in [`benchmarks/`](../benchmarks/README.md); this page maps each
headline metric to the script and method that generates it.

## Principles

1. **Everything is measured, nothing is assumed.** Each metric has a script that
   drives the real system and records samples.
2. **Improvements are stated as a pair (before → after).** The "after" is measured
   on the current system; the "before" baseline is reproduced by degrading one
   dimension and re-running the same script (see below).
3. **Percentiles, not averages, for latency.** Tail behaviour (p95/p99) is what
   matters for routing; the collector reports p50/p95/p99/max.

## Metric map

| Headline metric | Script | Method | Baseline procedure |
|---|---|---|---|
| Route-table propagation latency | `01_route_propagation.sh` | register → poll gateway route catalog until present; p95 over 100 runs | Compare against interval-based polling: set a long `health-check.interval-ms`, disable the Watch/Kafka push path, re-measure |
| Registry read throughput (req/s) | `02_read_throughput.sh` | concurrent load on `/services` through Redis `REPLICA_PREFERRED` | `kubectl scale deployment redis-replica --replicas=0` (master only) vs `--replicas=2` |
| Leader failover time | `03_leader_failover.sh` | evict partition-leader pod; time until a different replica owns the key; 50+ evictions | n/a (absolute) |
| Success rate during rolling restart | `04_rolling_restart.sh` | continuous gateway traffic during `rollout restart`; success ratio | run with Watch de-registration disabled for the baseline |
| Unhealthy detection latency | `05_watch_detection.sh` | delete pod; time until discovery flips status off `healthy` | n/a (absolute) |
| Onboarding time (deploy → routable) | `06_onboarding.sh` | `kubectl apply` service; time until gateway can route | compare against the manual gateway-edit workflow it replaces |

## Environment to record with results

Report these alongside numbers so they are interpretable and reproducible:

- Cluster: nodes, CPU, RAM (the full stack wants ~6–8 GB free).
- Image tags and `partition.count` / replica counts in effect.
- Redis topology (master + N replicas), Kafka partition count for `service-events`.
- `kubectl config current-context`.

`run_all.sh` captures the date, endpoints, and context automatically into
`results/REPORT.md`; append the hardware details manually.

## From report to résumé

Run `benchmarks/run_all.sh` on a cluster with enough headroom, then read the
final figures from `benchmarks/results/REPORT.md`. Each résumé metric maps to one
row of that report. Because the methodology above is written down and the scripts
are in the repo, every figure is defensible end to end — including the exact
command that produced it.

> Until the harness is run on suitable hardware, treat the specific figures in the
> project write-up as **targets to be confirmed**, not measured results. The
> harness is what turns them into measured results.
