# Architecture Decision Records

Short records of the significant design decisions in this system, each with the
context that forced the decision, the choice made, and its consequences.

---

## ADR-001 — Shared registry state in Redis

**Status:** Accepted

**Context.** The discovery service runs as a 3-replica `StatefulSet`. An early
version kept the service catalog in each replica's process memory, persisted to a
local JSON file. That made the catalog *per-replica*: two replicas answering
`/services` could return different results, and a replica that missed an update
served stale routes. Health status was kept roughly in sync through the Kafka and
Watch paths, but the catalog itself was not a single source of truth.

**Decision.** Persist the catalog in Redis via a Spring Data `@RedisHash`
repository (`ServiceRepository`). All reads and writes — `register`,
`getAllServices`, `getServiceByName`, `updateServiceStatus`, `deregister`, and the
health sweep — go through the repository. Per-replica memory is limited to
`HealthMetrics` (latency/success counters), which is observability, not
authoritative state.

**Consequences.**
- Every replica now serves an identical view; scaling replicas up or down no
  longer risks divergence. This is verified by `ConsistencyTest`.
- The periodic HTTP health sweep is guarded by a per-cycle Redis lock
  (`SET key val NX PX`) so exactly one replica sweeps per tick and writes results
  back to shared state, instead of three replicas racing.
- Redis becomes a hard dependency for the catalog. It is deployed master/replica
  with AOF persistence (see ADR-005) to bound the blast radius.

---

## ADR-002 — Per-partition leader election with Lua-fenced locks

**Status:** Accepted

**Context.** Kubernetes `Watch` connections and their downstream processing must
not be run by every replica simultaneously — that would multiply API-server load
and produce duplicate events. A single global leader would serialise all work
onto one replica and waste the other two.

**Decision.** Partition the services by a stable hash into `partition.count`
buckets and elect a leader *per partition*. Leadership is a Redis key with a TTL;
the holder renews it with a Lua compare-and-set script that extends the TTL only
if the key still holds the replica's own id, which fences out a stale former
leader. A monotonic generation counter is stamped on each leadership win and
carried on emitted events.

**Consequences.**
- Watch work spreads across replicas (one leader per partition) yet never
  double-runs.
- Failover is bounded by the TTL/renewal interval; measured by
  `benchmarks/03_leader_failover.sh`.
- The generation token lets consumers reason about event ordering across
  leadership changes.

---

## ADR-003 — Kafka event pipeline for route propagation

**Status:** Accepted

**Context.** The gateway must update its routing table when services register,
deregister, or change health. Polling the discovery service for the full catalog
on an interval is simple but couples propagation latency to the poll period.

**Decision.** The discovery service publishes `ServiceEvent`s to a Kafka topic
(`service-events`), keyed by service name. The gateway consumes them and mutates
its route definitions in place, emitting a `RefreshRoutesEvent`. The topic is
provisioned with an explicit partition count (3) via a `NewTopic` bean; keying by
service name keeps all events for one service ordered on a single partition while
distinct services spread across partitions.

**Consequences.**
- Propagation is push-based and sub-second (see
  `benchmarks/01_route_propagation.sh`), not tied to a poll interval.
- The gateway holds no static route configuration; routes are learned entirely
  from events, so there are zero hardcoded service URLs.
- Kafka is on the critical path for propagation; the gateway degrades to its last
  known routes if the broker is briefly unavailable.

---

## ADR-004 — Kubernetes Watch instead of polling for health

**Status:** Accepted

**Context.** Detecting that a pod became unready needs to be fast to keep the
gateway from routing to dead instances during rollouts and evictions. Polling the
API server for pod lists on an interval adds latency equal to (at best) the poll
period and load proportional to `services × replicas ÷ period`.

**Decision.** The partition leader opens a streaming `Watch` against the pod API
(`watch=true`, resuming from the last `resourceVersion`, handling `410 Gone` by
resetting), translating pod phase/readiness transitions directly into status
updates. Followers stay warm by subscribing to a Redis pub/sub relay of processed
events, so a new leader does not start cold.

**Consequences.**
- Detection latency drops to the event round-trip rather than a poll period
  (`benchmarks/05_watch_detection.sh`).
- Success rate during rolling restarts improves because terminating pods are
  de-routed quickly (`benchmarks/04_rolling_restart.sh`).
- The old polling path (`KubernetesPollingService`) is retained only as a manual
  fallback and is no longer scheduled.

---

## ADR-005 — Redis master/replica for read scaling

**Status:** Accepted

**Context.** With the catalog in Redis, catalog reads (from every replica's
controller and health sweep, plus external `/services` traffic) concentrate on
Redis. A single node caps read throughput.

**Decision.** Deploy Redis as one master and two replicas. The Lettuce client is
configured `ReadFrom.REPLICA_PREFERRED`, so writes go to the master and reads fan
out across replicas. Pub/sub uses a dedicated master connection (pub/sub is not
served by replicas). AOF persistence is enabled on all nodes.

**Consequences.**
- Read throughput scales with replica count; quantified by
  `benchmarks/02_read_throughput.sh` (compare replicas=0 vs replicas=2).
- Writes remain single-master, preserving a simple consistency model.
- A replica lagging the master can briefly serve slightly stale reads — acceptable
  for a discovery catalog whose entries are themselves eventually consistent.
