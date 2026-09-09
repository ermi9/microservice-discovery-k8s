#!/usr/bin/env bash
# Request success rate during a rolling restart.
#
# Sends continuous traffic through the gateway to a target service while that
# service undergoes `kubectl rollout restart`, then reports the success rate.
# With Watch-driven de-registration the gateway stops routing to terminating
# pods quickly, so in-flight requests avoid connection failures.
#
# This backs the "request success rate during rolling restarts" metric. To
# capture the baseline, disable the watch/de-registration path (see
# benchmarks/README.md) and re-run.
#
# Env: TARGET (default service-a), CONCURRENCY (default 30), DURATION (default 40)
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh
require kubectl; require python3

TARGET="${TARGET:-service-a}"
CONCURRENCY="${CONCURRENCY:-30}"
DURATION="${DURATION:-40}"
ROUTE="$GATEWAY_URL/route/$TARGET/health"
OUT="$RESULTS_DIR/rolling_restart.json"

log "Rolling restart: hammering $ROUTE for ${DURATION}s while restarting $TARGET"
python3 lib/loadgen.py --url "$ROUTE" --concurrency "$CONCURRENCY" --duration "$DURATION" > "$OUT" &
LOAD_PID=$!

# Kick the restart a few seconds in, so we capture steady-state before + during.
sleep 5
kubectl rollout restart deployment/"$TARGET" -n "$NAMESPACE"
kubectl rollout status  deployment/"$TARGET" -n "$NAMESPACE" --timeout=120s || true

wait "$LOAD_PID"

python3 - "$OUT" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
ok, err = r["requests"], r["errors"]
total = ok + err
rate = (100.0 * ok / total) if total else 0.0
print(f"Requests: {total}  ok: {ok}  errors: {err}  success_rate: {rate:.2f}%")
PY
