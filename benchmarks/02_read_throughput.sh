#!/usr/bin/env bash
# Registry read throughput.
#
# Drives concurrent reads at the discovery service's /services endpoint, which
# reads through the Redis master/replica topology (reads are routed to replicas
# via ReadFrom.REPLICA_PREFERRED). Reports sustained requests/sec and latency
# percentiles.
#
# To demonstrate the read-scaling factor, run this once with the replicas up
# (master + 2 replicas) and once with them scaled to zero (master only), then
# compare throughput_rps:
#     kubectl scale deployment redis-replica --replicas=0   # baseline: master only
#     kubectl scale deployment redis-replica --replicas=2   # scaled: master + replicas
#
# Env: CONCURRENCY (default 50), DURATION seconds (default 20)
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
require curl; require python3

CONCURRENCY="${CONCURRENCY:-50}"
DURATION="${DURATION:-20}"
OUT="$RESULTS_DIR/throughput.json"

log "Read throughput: concurrency=$CONCURRENCY duration=${DURATION}s against $DISCOVERY_URL/services"
python3 lib/loadgen.py \
    --url "$DISCOVERY_URL/services" \
    --concurrency "$CONCURRENCY" \
    --duration "$DURATION" | tee "$OUT"
