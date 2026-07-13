#!/usr/bin/env bash
# Leader failover time under pod eviction.
#
# Repeatedly evicts the discovery-service pod that currently holds partition-0
# leadership (read straight from the Redis leadership key) and measures how long
# until a *different* replica acquires it. Each iteration is one pod-eviction
# test; run 50+ to characterise the distribution.
#
# This backs the "sub-second failover across N pod eviction tests" metric.
#
# Env: ITER (default 50), PARTITION_KEY (default discovery:partition:0:leader)
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
require kubectl

ITER="${ITER:-50}"
PARTITION_KEY="${PARTITION_KEY:-discovery:partition:0:leader}"
SAMPLES="$RESULTS_DIR/failover_ms.txt"
: > "$SAMPLES"

log "Leader failover: $ITER evictions, watching key '$PARTITION_KEY'"
for i in $(seq 1 "$ITER"); do
    old_leader="$(leader_for "$PARTITION_KEY")"
    if [ -z "$old_leader" ]; then
        log "iter $i: no current leader, waiting for one to appear"
        sleep 2; continue
    fi

    kubectl delete pod -n "$NAMESPACE" "$old_leader" --grace-period=0 --force >/dev/null 2>&1 || true

    # Time until a different replica owns the partition.
    if ms="$(wait_until_changed 15000 50 "$old_leader" leader_for "$PARTITION_KEY")"; then
        echo "$ms" >> "$SAMPLES"
        log "iter $i: $old_leader -> $(leader_for "$PARTITION_KEY") in ${ms}ms"
    else
        log "iter $i: no new leader within 15s (recorded as timeout, excluded)"
    fi

    # Let the StatefulSet replace the evicted pod before the next round.
    kubectl rollout status statefulset/discovery-service -n "$NAMESPACE" --timeout=120s >/dev/null 2>&1 || true
done

python3 lib/collector.py --label "Leader failover" --unit ms "$SAMPLES"
