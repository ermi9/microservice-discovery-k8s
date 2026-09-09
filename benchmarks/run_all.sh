#!/usr/bin/env bash
# Runs every benchmark scenario in sequence and assembles results/REPORT.md.
# Individual scenarios can also be run on their own; see benchmarks/README.md.
set -uo pipefail
cd "$(dirname "$0")"
. lib/common.sh

REPORT="$RESULTS_DIR/REPORT.md"
log "Writing consolidated report to $REPORT"

{
    echo "# Benchmark Report"
    echo
    echo "- Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Discovery URL: \`$DISCOVERY_URL\`  Gateway URL: \`$GATEWAY_URL\`"
    echo "- Cluster: \`$(kubectl config current-context 2>/dev/null || echo unknown)\`"
    echo
    echo "## Timing metrics (percentiles, ms)"
    echo
    echo "| Metric | n | p50 | p95 | p99 | max | unit |"
    echo "|---|---|---|---|---|---|---|"
} > "$REPORT"

run_timing() { # <script>
    log "=== $1 ==="
    bash "$1" | tee -a /dev/stderr | grep '^|' >> "$REPORT" || log "$1 produced no table row"
}

run_timing 01_route_propagation.sh
run_timing 03_leader_failover.sh
run_timing 05_watch_detection.sh
run_timing 06_onboarding.sh

{
    echo
    echo "## Throughput"
    echo
    log "=== 02_read_throughput.sh ==="
} >> "$REPORT"
bash 02_read_throughput.sh > "$RESULTS_DIR/_throughput.out" 2>/dev/null || true
if [ -s "$RESULTS_DIR/throughput.json" ]; then
    python3 - "$RESULTS_DIR/throughput.json" >> "$REPORT" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
lat = r["latency_ms"]
print(f"- Sustained throughput: **{r['throughput_rps']} req/s** "
      f"(concurrency {r['concurrency']}, {r['requests']} requests, {r['errors']} errors)")
print(f"- Read latency: p50 {lat['p50']}ms, p95 {lat['p95']}ms, p99 {lat['p99']}ms")
PY
fi

{
    echo
    echo "## Availability during rolling restart"
    echo
    log "=== 04_rolling_restart.sh ==="
} >> "$REPORT"
bash 04_rolling_restart.sh | tail -1 | sed 's/^/- /' >> "$REPORT" || true

log "Done. Report at $REPORT"
cat "$REPORT" >&2
