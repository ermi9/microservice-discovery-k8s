# EDA Microservice Discovery System

An event-driven microservice discovery and routing system built on Kubernetes. Four business services (order, inventory, payment, shipping) register themselves on startup, the discovery service tracks their health via Kubernetes Watch streams, and an API gateway updates its routes automatically through a Kafka event pipeline.

The registry is Redis-backed and shared by all replicas, so every replica returns the same answer. The Kafka topic `service-events` is the platform's outward contract: it is log-compacted and keyed by service name, so any consumer can rebuild its entire view by replaying it from offset 0. Consumers deserialize by JSON schema into their own local DTO — no Java type is shared across module boundaries.

---

## Where to Find Things

| What you need | Where to look |
|---|---|
| Full project report (architecture, design decisions, deployment) | `docs/FINAL_REPORT_v3.docx` |
| Gateway vs Kong / Traefik / AWS API Gateway / Nginx | `docs/GATEWAY_COMPARISON.docx` |
| Data consistency analysis and failure scenarios | `docs/CONSISTENCY_REPORT_v3.docx` |
| Architecture diagrams and component reference (in-repo) | `docs/ARCHITECTURE.md` |
| Architecture decision records (why each choice was made) | `docs/DECISIONS.md` |
| Operations runbook (deploy, scale, recover) | `docs/RUNBOOK.md` |
| Benchmark methodology (how every number is measured) | `docs/BENCHMARKS.md` |
| Reproducible benchmark harness | `benchmarks/` |
| Configuration reference with all tuneable properties | `docs/application.properties.example` |
| Discovery service source | `discovery-service/src/` |
| API gateway source | `api-gateway/src/` |
| Order service (service-a) source | `service-a/src/` |
| Inventory service (service-b) source | `service-b/src/` |
| Payment service (service-c) source | `service-c/src/` |
| Shipping service (service-d) source | `service-d/src/` |
| Kubernetes manifests | `k8s/` |
| Docker Compose (full local stack) | `discovery-service/docker-compose.yml` |

---

## Quick Start

### Local (Docker Compose)

```bash
# Build all images (tags match docker-compose.yml and the k8s manifests)
docker build -t discovery-service:v4 ./discovery-service
docker build -t api-gateway:latest   ./api-gateway
docker build -t service-a:latest     ./service-a   # order
docker build -t service-b:latest     ./service-b   # inventory
docker build -t service-c:latest     ./service-c   # payment
docker build -t service-d:latest     ./service-d   # shipping

# Start the full stack (Redis master+replica, Kafka, discovery, gateway, 4 services)
docker compose -f discovery-service/docker-compose.yml up
```

### Kubernetes (Minikube)

```bash
eval $(minikube docker-env)

docker build -t discovery-service:v4 ./discovery-service
docker build -t api-gateway:latest ./api-gateway
docker build -t service-a:latest ./service-a
docker build -t service-b:latest ./service-b
docker build -t service-c:latest ./service-c
docker build -t service-d:latest ./service-d

kubectl apply -f k8s/kafka.yaml
kubectl apply -f k8s/

# Adjust partition count and replica count
./configure-cluster.sh [partition-count] [replica-count]
```

---

## configure-cluster.sh

Scales the discovery service replicas and sets the partition count without rebuilding any image:

```bash
./configure-cluster.sh 3 3   # 3 partitions, 3 replicas (default)
./configure-cluster.sh 5 5   # scale up
./configure-cluster.sh 3 1   # single replica for local testing
```

---

## Try It Out

```bash
# List registered services — identical on every replica (Redis-backed)
curl http://localhost:8080/services | jq

# Is this replica able to serve? (checks Redis; /health is liveness only)
curl http://localhost:8080/ready | jq

# Probe statistics this replica collected for a service
curl http://localhost:8080/services/service-b/metrics | jq

# Gateway route catalog (includes OpenAPI URL per service)
curl http://localhost:8083/services | jq

# Fetch the inventory service's live OpenAPI spec through the gateway
curl http://localhost:8083/openapi/service-b | jq

# Browse inventory
curl http://localhost:8083/route/service-b/products | jq

# Place an order (service-a checks stock via gateway, deducts if available)
curl -X POST http://localhost:8082/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"P001","quantity":2,"customer":"Alice"}' | jq

# Try ordering out-of-stock item (P003 stock=0)
curl -X POST http://localhost:8082/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"P003","quantity":1,"customer":"Bob"}' | jq

# Capture a payment for an order (service-c, via gateway)
curl -X POST http://localhost:8083/route/service-c/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1","amount":59.98}' | jq

# Dispatch a shipment (service-d, via gateway)
curl -X POST http://localhost:8083/route/service-d/shipments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1","address":"12 Via Roma, Messina"}' | jq
```

---

## Project Structure

```
.
├── configure-cluster.sh               # Adjust partitions and replicas in K8s
├── api-gateway/                       # Spring Cloud Gateway — Kafka-driven, OpenAPI pass-through
├── discovery-service/                 # Redis-backed shared registry — leader election, K8s Watch,
│                                      #   Kafka publisher
│   └── docker-compose.yml             # Full local stack
├── service-a/                         # Order service — calls inventory via gateway
├── service-b/                         # Inventory service — products, stock reservation
├── service-c/                         # Payment service — captures payment for an order
├── service-d/                         # Shipping service — dispatches shipments, issues tracking
├── k8s/                               # Kubernetes manifests (StatefulSet, Redis, Kafka, Gateway)
└── docs/
    ├── FINAL_REPORT_v3.docx           # Full project report — architecture, design, deployment
    ├── CONSISTENCY_REPORT_v3.docx     # Data consistency analysis and failure scenarios
    ├── GATEWAY_COMPARISON.docx        # EDA gateway vs Kong, Traefik, AWS API GW, Nginx
    ├── ARCHITECTURE.md                # Architecture diagrams and component reference
    └── application.properties.example # Configuration reference for all tuneable properties
```

---

## Maven Artifacts

| Module | groupId | artifactId |
|--------|---------|------------|
| Discovery Service | `com.eda` | `eda-discovery-service` |
| API Gateway | `com.eda` | `eda-api-gateway` |
| Order Service (service-a) | `com.eda` | `eda-order-service` |
| Inventory Service (service-b) | `com.eda` | `eda-inventory-service` |
| Payment Service (service-c) | `com.eda` | `eda-payment-service` |
| Shipping Service (service-d) | `com.eda` | `eda-shipping-service` |
