#!/usr/bin/env bash
# Watch detection latency (unhealthy instance -> de-registered).
#
# Deletes a target service's pod and measures how long until the discovery
# service — driven by its Kubernetes Watch stream — flips that service's status
# away from "healthy" (to not-ready/unavailable). Backs the "detecting and
# de-registering unhealthy instances within N seconds" metric.
#
# Env: TARGET (default service-a), ITER (default 30)
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
require kubectl; require curl

TARGET="${TARGET:-service-a}"
ITER="${ITER:-30}"
SAMPLES="$RESULTS_DIR/watch_detection_ms.txt"
: > "$SAMPLES"

log "Watch detection: $ITER iterations on $TARGET"
for i in $(seq 1 "$ITER"); do
    # Ensure it is healthy first.
    until discovery_status_is "$TARGET" "healthy"; do sleep 1; done

    pod="$(kubectl get pod -n "$NAMESPACE" -l "app=$TARGET" \
            -o jsonpath='{.items[0].metadata.name}')"
    start="$(now_ms)"
    kubectl delete pod -n "$NAMESPACE" "$pod" --grace-period=0 --force >/dev/null 2>&1 || true

    # Poll discovery until the status is no longer "healthy".
    while discovery_status_is "$TARGET" "healthy"; do
        [ $(( $(now_ms) - start )) -ge 15000 ] && { log "iter $i: timeout"; break; }
    done
    echo $(( $(now_ms) - start )) >> "$SAMPLES"
    log "iter $i: detected in $(tail -1 "$SAMPLES")ms"

    kubectl rollout status deployment/"$TARGET" -n "$NAMESPACE" --timeout=120s >/dev/null 2>&1 || true
done

python3 lib/collector.py --label "Watch detection" --unit ms "$SAMPLES"
