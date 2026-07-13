# Benchmark Harness

A reproducible harness that measures the performance characteristics of the EDA
Microservice Discovery System end-to-end against a running cluster. Every metric
quoted for this project comes from these scripts — nothing is hand-estimated.

The harness is intentionally dependency-free: **bash + `kubectl` + `curl` +
Python 3 standard library**. No `pip install`, no `hey`/`wrk`.

## What each scenario measures

| Script | Metric | How it is measured |
|---|---|---|
| `01_route_propagation.sh` | Route-table propagation latency | Register a fresh service, poll the gateway route catalog until it appears; time the `register → Kafka → gateway RefreshRoutes` pipeline. p50/p95/p99 over `ITER` runs. |
| `02_read_throughput.sh` | Registry read throughput (req/s) | Concurrent load on `/services`, which reads through the Redis master/replica topology (`REPLICA_PREFERRED`). |
| `03_leader_failover.sh` | Leader failover time | Evict the pod holding the partition-0 leadership key (read from Redis); time until a different replica acquires it. One iteration = one pod-eviction test. |
| `04_rolling_restart.sh` | Request success rate during restart | Continuous traffic through the gateway while the target service is `rollout restart`ed; report success ratio. |
| `05_watch_detection.sh` | Unhealthy-instance detection latency | Delete a pod; time until the Watch stream flips the service status away from `healthy`. |
| `06_onboarding.sh` | Onboarding time (deploy → routable) | `kubectl apply` a service; time until the gateway can route to it. |

## Running it

Prereqs: a deployed cluster (see the root `README.md` for `minikube`/`kubectl apply`),
then port-forward the two HTTP entry points in separate terminals:

```bash
kubectl port-forward svc/discovery-service 8080:8080
kubectl port-forward svc/api-gateway       8083:8080
```

Then, from this directory:

```bash
# Everything, writing results/REPORT.md
./run_all.sh

# Or a single scenario with overrides
ITER=200 ./01_route_propagation.sh
CONCURRENCY=100 DURATION=30 ./02_read_throughput.sh
ITER=50 ./03_leader_failover.sh
```

Endpoints and namespace are configurable via env: `DISCOVERY_URL`, `GATEWAY_URL`,
`NAMESPACE`, `REDIS_SELECTOR`. Defaults target the port-forwards above.

## Establishing the "before" baselines

Several resume-facing numbers are stated as an improvement (X → Y). The harness
measures the current (Y) system directly; capture the baseline (X) by degrading
one dimension and re-running the same script:

- **Propagation vs. polling.** The pre-EDA design refreshed routes by re-scanning
  state on a fixed interval (`health-check.interval-ms`, default 15000). The
  expected wait for a change to surface under polling is on the order of half the
  interval; set a long interval and disable the Watch/Kafka path to reproduce it,
  then compare against the event-driven p95 from `01_route_propagation.sh`.
- **Read throughput scaling.** Run `02` with `kubectl scale deployment
  redis-replica --replicas=0` (master-only baseline) and again with
  `--replicas=2` (master + replicas), then compare `throughput_rps`.
- **Restart success rate.** Run `04` with Watch-driven de-registration active,
  then with it disabled, to contrast the success ratio.

Record both numbers; the resume figure is the pair.

## Output

- Per-scenario raw samples: `results/*_ms.txt`, `results/throughput.json`
- Consolidated table: `results/REPORT.md` (see `results/REPORT.template.md` for shape)

> Fill the resume metrics from `results/REPORT.md` once you have run this on a
> cluster with enough headroom (the full stack wants ~6–8 GB RAM). Whatever the
> numbers are, they are then defensible line-by-line.
