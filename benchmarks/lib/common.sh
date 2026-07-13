# Shared helpers for the benchmark scripts. Source this: `. lib/common.sh`
#
# Endpoints are configurable so the harness runs against either a port-forwarded
# Kubernetes cluster or the docker-compose stack.
#
#   DISCOVERY_URL   default http://localhost:8080   (discovery-service)
#   GATEWAY_URL     default http://localhost:8083   (api-gateway)
#   NAMESPACE       default default                 (k8s namespace)
#   REDIS_SELECTOR  default app=redis-master        (pod to run redis-cli in)
#
# For a Kubernetes run, first open the port-forwards in another terminal:
#   kubectl port-forward svc/discovery-service 8080:8080
#   kubectl port-forward svc/api-gateway       8083:8080

set -u

DISCOVERY_URL="${DISCOVERY_URL:-http://localhost:8080}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8083}"
NAMESPACE="${NAMESPACE:-default}"
REDIS_SELECTOR="${REDIS_SELECTOR:-app=redis-master}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_DIR="${RESULTS_DIR:-$HERE/results}"
mkdir -p "$RESULTS_DIR"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Epoch milliseconds (portable enough for GNU date).
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

require() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

# Run redis-cli inside the redis-master pod and echo the result.
redis_cli() {
    local pod
    pod="$(kubectl get pod -n "$NAMESPACE" -l "$REDIS_SELECTOR" \
            -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
    [ -n "$pod" ] || die "no pod matches selector '$REDIS_SELECTOR' in ns '$NAMESPACE'"
    kubectl exec -n "$NAMESPACE" "$pod" -- redis-cli "$@" 2>/dev/null
}

# The pod name currently holding a leadership key. Args: the redis key.
#   leader_for discovery:leader
#   leader_for discovery:partition:0:leader
leader_for() { redis_cli get "$1" | tr -d '\r'; }

# Poll `cmd` until it prints the expected value or timeout (ms). Prints elapsed ms
# on success (exit 0); prints nothing and exits 1 on timeout.
#   wait_for_change <timeout_ms> <poll_ms> <cmd...>   (succeeds when cmd stdout != BASELINE)
wait_until_changed() {
    local timeout_ms="$1" poll_ms="$2" baseline="$3"; shift 3
    local start; start="$(now_ms)"
    while :; do
        local cur; cur="$("$@")"
        if [ "$cur" != "$baseline" ] && [ -n "$cur" ]; then
            echo $(( $(now_ms) - start )); return 0
        fi
        [ $(( $(now_ms) - start )) -ge "$timeout_ms" ] && return 1
        sleep "$(awk "BEGIN{print $poll_ms/1000}")"
    done
}

# HTTP GET, printing the body (empty string on failure).
http_get() { curl -fsS --max-time 5 "$1" 2>/dev/null || true; }

# True if the gateway route table currently lists the given service name.
gateway_has_route() { http_get "$GATEWAY_URL/services" | grep -q "\"$1\""; }

# True if discovery lists the service with the given status substring.
discovery_status_is() { http_get "$DISCOVERY_URL/services/$1" | grep -q "\"$2\""; }
