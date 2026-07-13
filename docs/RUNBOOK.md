# Operations Runbook

Operational procedures for deploying, scaling, and recovering the EDA Microservice
Discovery System. Assumes a Kubernetes cluster (minikube for local) and `kubectl`
context pointed at it.

## Components and ports

| Component | Kind | Replicas | Port | Depends on |
|---|---|---|---|---|
| discovery-service | StatefulSet | 3 | 8080 | redis-master, redis-replica, kafka |
| api-gateway | Deployment | 1 | 8080 (8083 fwd) | kafka |
| service-a … service-d | Deployment | 1 each | 8080 | discovery-service |
| redis-master | Deployment | 1 | 6379 | — |
| redis-replica | Deployment | 2 | 6379 | redis-master |
| kafka | Deployment | 1 | 9092 | — |

## First deploy

```bash
eval $(minikube docker-env)
docker build -t discovery-service:v4 ./discovery-service
docker build -t api-gateway:latest ./api-gateway
for s in a b c d; do docker build -t service-$s:latest ./service-$s; done

kubectl apply -f k8s/kafka.yaml
kubectl apply -f k8s/
kubectl rollout status statefulset/discovery-service --timeout=180s
```

Verify:

```bash
kubectl get pods
kubectl port-forward svc/discovery-service 8080:8080 &
kubectl port-forward svc/api-gateway       8083:8080 &
curl -s localhost:8080/services | jq 'length'   # expect 4 once services register
curl -s localhost:8083/services | jq            # gateway route catalog
```

## Scaling

**Discovery replicas / partitions** — use the helper, which sets the partition
count env and scales the StatefulSet without rebuilding images:

```bash
./configure-cluster.sh 3 3     # 3 partitions, 3 replicas (default)
./configure-cluster.sh 5 5     # scale up
```

Rule of thumb: keep `replicas >= partitions` so every partition can have a leader.

**Redis read capacity** — scale replicas; reads are `REPLICA_PREFERRED` and pick
up new replicas automatically:

```bash
kubectl scale deployment redis-replica --replicas=3
```

## Common tasks

**Inspect leadership.** Leadership lives in Redis keys:

```bash
POD=$(kubectl get pod -l app=redis-master -o jsonpath='{.items[0].metadata.name}')
kubectl exec "$POD" -- redis-cli get discovery:leader
kubectl exec "$POD" -- redis-cli get discovery:partition:0:leader
kubectl exec "$POD" -- redis-cli keys 'discovery:*'
```

**Inspect the catalog directly.**

```bash
kubectl exec "$POD" -- redis-cli keys 'services*'
```

**Tail discovery logs for election/watch activity.**

```bash
kubectl logs -l app=discovery-service --prefix -f | grep -E 'Election|Watch|Relay'
```

## Failure scenarios and recovery

| Symptom | Likely cause | Action |
|---|---|---|
| `/services` empty after deploy | services not yet started / can't reach discovery | check service pods are `Running`; check `DISCOVERY_SERVICE_URL`; look for "Successfully registered" in service logs |
| Gateway not routing a live service | gateway missed/behind on Kafka | `kubectl logs -l app=api-gateway | grep Route`; confirm topic `service-events` exists with 3 partitions |
| Two replicas both watching a partition | stale leadership key (should self-heal via TTL) | inspect `discovery:partition:N:leader`; delete the key to force re-election |
| Stale routes after pod eviction | Watch stream stalled | check discovery logs for `[Watch] Stream closed`/`410`; the loop reconnects automatically; if not, restart the leader pod |
| Redis master down | AOF replay on restart | master restarts and replays AOF; replicas re-sync; catalog is preserved |

## Rolling restart safety

Rolling restarts are exercised by `benchmarks/04_rolling_restart.sh`. The preStop
hook on each service issues a `DELETE /services/<name>` to the discovery service,
and the Watch stream independently observes the pod going unready — both remove
the route quickly so in-flight requests are not sent to a terminating pod.

## Teardown

```bash
kubectl delete -f k8s/
kubectl delete -f k8s/kafka.yaml
```
