# Benchmark Report (template)

This is the shape `run_all.sh` produces. Real numbers are filled in on a run.

- Date: <UTC timestamp>
- Discovery URL: `http://localhost:8080`  Gateway URL: `http://localhost:8083`
- Cluster: `<kubectl context>`

## Timing metrics (percentiles, ms)

| Metric | n | p50 | p95 | p99 | max | unit |
|---|---|---|---|---|---|---|
| Route propagation | 100 | … | … | … | … | ms |
| Leader failover | 50 | … | … | … | … | ms |
| Watch detection | 30 | … | … | … | … | ms |
| Onboarding (deploy->routable) | 20 | … | … | … | … | ms |

## Throughput

- Sustained throughput: **… req/s** (concurrency 50, … requests, … errors)
- Read latency: p50 …ms, p95 …ms, p99 …ms

## Availability during rolling restart

- Requests: …  ok: …  errors: …  success_rate: …%
