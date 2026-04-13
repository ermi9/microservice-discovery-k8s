# K8s Service Discovery Framework

A production-ready microservice discovery system for Kubernetes with configurable health monitoring and automatic service registration.

## Quick Start

1. Deploy to Kubernetes:
```bash
kubectl apply -f k8s/
```

2. Services auto-register on startup (already configured in service-a and service-b)

3. Query the registry:
```bash
curl http://discovery-service:8080/services | jq
```

## Key Features

- ✅ Auto-registration on service startup
- ✅ Automatic health monitoring with configurable retry logic
- ✅ Kubernetes pod state tracking
- ✅ REST API for service discovery
- ✅ Production-grade resilience (retries, failure thresholds)

## Configuration

All settings in `discovery-service/src/main/resources/application.properties`:

```properties
health-check.interval-ms=15000          # Check every 15 seconds
health-check.max-retries=2              # Retry 2 times on failure
health-check.failure-threshold=3        # Mark unhealthy after 3 failures
health-check.timeout-ms=5000            # Request timeout
```

## REST API
GET  /services              # All services with pod info
GET  /services/{name}       # Specific service details
POST /register              # Auto-called on service startup
GET  /health                # Health probe

## For More Details

See the `docs/` folder:
- **ARCHITECTURE.md** - System design, data flows, design decisions
- **application.properties.example** - Documented configuration options with examples

## Project Structure
.
├── discovery-service/       # Central discovery service (Spring Boot)
├── service-a/              # Example microservice A
├── service-b/              # Example microservice B
├── k8s/                    # Kubernetes manifests
├── docs/                   # Detailed documentation
└── README.md              # This file

## Technologies

- Spring Boot 4.0.5
- Kubernetes Java Client 24.0.0
- Minikube (for local development)
- Docker
