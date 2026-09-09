#!/usr/bin/env bash
# Route-table propagation latency (event-driven path).
#
# Measures the time from a service registering with the discovery service until
# the API gateway's route table reflects it — i.e. the full
# register -> Kafka(service-events) -> gateway consumer -> RefreshRoutes pipeline.
#
# This is the "route-table propagation latency" metric. Compare the p95 here
# against the polling baseline documented in benchmarks/README.md (the pre-EDA
# design re-scanned state on a fixed 15s interval).
#
# Env: ITER (default 100)
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
require curl; require kubectl

ITER="${ITER:-100}"
SAMPLES="$RESULTS_DIR/propagation_ms.txt"
: > "$SAMPLES"

log "Route propagation: $ITER iterations against $GATEWAY_URL"
for i in $(seq 1 "$ITER"); do
    name="bench-prop-$i-$RANDOM"
    url="http://$name:8080"
    start="$(now_ms)"
    curl -fsS --max-time 5 -X POST "$DISCOVERY_URL/register" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"$name\",\"url\":\"$url\",\"openapiUrl\":\"$url/v3/api-docs\"}" >/dev/null

    # Poll the gateway route catalog until the new service appears.
    while ! gateway_has_route "$name"; do
        [ $(( $(now_ms) - start )) -ge 10000 ] && { log "timeout on $name"; break; }
    done
    echo $(( $(now_ms) - start )) >> "$SAMPLES"

    # Clean up so the registry does not accumulate throwaway entries.
    curl -fsS --max-time 5 -X DELETE "$DISCOVERY_URL/services/$name" >/dev/null || true
done

python3 lib/collector.py --label "Route propagation" --unit ms "$SAMPLES"
