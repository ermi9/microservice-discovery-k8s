#!/usr/bin/env bash
# Service onboarding time (deploy -> routable).
#
# Applies a service's Kubernetes manifests and measures the time until the
# gateway can route to it — i.e. the pod started, self-registered, the event
# propagated, and the route went live. Backs the "service onboarding in under
# N seconds" metric (the event-driven successor to manual gateway edits).
#
# The service is deleted first so each run measures a true cold onboarding.
#
# Env: TARGET (default service-c), ITER (default 20)
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
require kubectl; require curl

TARGET="${TARGET:-service-c}"
ITER="${ITER:-20}"
K8S_DIR="${K8S_DIR:-../k8s}"
SAMPLES="$RESULTS_DIR/onboarding_ms.txt"
: > "$SAMPLES"

log "Onboarding: $ITER iterations for $TARGET"
for i in $(seq 1 "$ITER"); do
    kubectl delete -f "$K8S_DIR/$TARGET-deployment.yaml" -n "$NAMESPACE" >/dev/null 2>&1 || true
    curl -fsS -X DELETE "$DISCOVERY_URL/services/$TARGET" >/dev/null 2>&1 || true
    while gateway_has_route "$TARGET"; do sleep 0.5; done

    start="$(now_ms)"
    kubectl apply -f "$K8S_DIR/$TARGET-deployment.yaml" -n "$NAMESPACE" >/dev/null
    while ! gateway_has_route "$TARGET"; do
        [ $(( $(now_ms) - start )) -ge 60000 ] && { log "iter $i: timeout"; break; }
    done
    echo $(( $(now_ms) - start )) >> "$SAMPLES"
    log "iter $i: routable in $(tail -1 "$SAMPLES")ms"
done

python3 lib/collector.py --label "Onboarding (deploy->routable)" --unit ms "$SAMPLES"
