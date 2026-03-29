# Microservice Discovery and Kubernetes Service Resolution

A practical implementation of microservice discovery patterns, progressing from Docker to Kubernetes. This project demonstrates how services register themselves, discover each other, and maintain health in containerized environments.

## Project Overview

This system consists of three Spring Boot services:

- **Service A** (`service-a/`): Accepts requests and calls Service B
- **Service B** (`service-b/`): Provides data via REST API
- **Discovery Service** (`discovery-service/`): Central registry for service metadata and API specs

## Architecture

### The Problem Being Solved

When services run in containers, they can't use `localhost` to reach each other because each container has its own isolated network namespace. This project demonstrates two solutions:

1. **Docker Solution**: Manual service registration + environment-based configuration
2. **Kubernetes Solution**: Built-in DNS discovery + automatic health checks

### Key Components

#### Service Registration (No Hardcoding)

Services use `@Value` annotations to inject configuration from environment variables:

```java
@Value("${service.b.url:http://localhost:8081}")
private String serviceBUrl;

@Value("${discovery.service.url:http://localhost:8080}")
private String discoveryServiceUrl;
```

Instead of hardcoding URLs, services get them from their environment. This works identically in Docker and Kubernetes.

#### Automatic Discovery

When a service starts, it automatically registers itself:

```java
@EventListener(ApplicationReadyEvent.class)
public void registerWithDiscoveryService() {
    Map<String, String> request = new HashMap<>();
    request.put("name", appName);
    request.put("url", serviceUrl);
    request.put("openapiUrl", serviceUrl + "/api/openapi.json");
    
    String registerUrl = discoveryServiceUrl + "/register";
    restTemplate.postForObject(registerUrl, request, Map.class);
}
```

The `DiscoveryRegistration` component is reusable — any new service can include it with just environment variables.

#### API Metadata Discovery

Each service exposes an OpenAPI spec at `/api/openapi.json`. The discovery service tracks this URL so clients can:

- Discover what endpoints exist
- Understand parameters and response types
- Build correct integration code

## Running in Docker

```bash
# Build images
docker build -t service-a:latest ./service-a/
docker build -t service-b:latest ./service-b/
docker build -t discovery-service:v2 ./discovery-service/

# Create network
docker network create discovery-network

# Run services
docker run --network discovery-network --name discovery-service -p 8080:8080 \
  discovery-service:v2

docker run --network discovery-network --name service-b -p 8081:8080 \
  -e APP_NAME=service-b \
  -e APP_SERVICE_URL=http://service-b:8080 \
  -e DISCOVERY_SERVICE_URL=http://discovery-service:8080 \
  service-b:latest

docker run --network discovery-network --name service-a -p 8082:8080 \
  -e APP_NAME=service-a \
  -e APP_SERVICE_URL=http://service-a:8080 \
  -e SERVICE_B_URL=http://service-b:8080 \
  -e DISCOVERY_SERVICE_URL=http://discovery-service:8080 \
  service-a:latest

# Test
curl http://localhost:8082/call?name=test
curl http://localhost:8080/services
```

## Running in Kubernetes

### Prerequisites

- Kubernetes cluster (minikube works)
- kubectl configured
- Images loaded or available in a registry

### Deploy

```bash
# Load images into minikube
minikube image load service-a:latest
minikube image load service-b:latest
minikube image load discovery-service:v2

# Apply manifests
kubectl apply -f k8s/

# Check status
kubectl get pods
kubectl get svc

# Test via port-forward
kubectl port-forward svc/service-a 8080:8080
# In another terminal:
curl http://localhost:8080/call?name=test
```

## How Kubernetes Solves Docker Problems

### Problem 1: Manual Service Discovery

**Docker**: Services register themselves with a discovery service. Manual polling could track health.

**Kubernetes**: CoreDNS automatically resolves service names. You just reference `service-b:8080` and Kubernetes routes it to the actual Pod.

### Problem 2: Health Monitoring

**Docker**: A discovery service would need to poll every service manually.

**Kubernetes**: Readiness and liveness probes are built-in:
- **Readiness Probe** (every 5s): Is the Pod ready to serve? Remove from traffic if not.
- **Liveness Probe** (every 10s): Is the Pod alive? Restart if not.

### Problem 3: Manual Container Management

**Docker**: You manually start/stop containers, map ports, pass environment variables.

**Kubernetes**: You declare desired state in manifests. Kubernetes ensures that state continuously.

## Files and Their Purpose

### Service A (`service-a/`)
- `src/main/java/com/example/testProj/serviceA/CallController.java`: HTTP endpoint that calls Service B
- `src/main/java/com/example/testProj/discovery/DiscoveryRegistration.java`: Auto-registers with discovery service
- `pom.xml`: Maven dependencies (Spring Boot, Jackson for JSON)
- `Dockerfile`: Container definition

### Service B (`service-b/`)
- `src/main/java/com/example/testProj/GreetingController.java`: HTTP endpoint returning data
- `src/main/java/com/example/testProj/discovery/DiscoveryRegistration.java`: Same auto-registration component
- `pom.xml`: Maven dependencies
- `Dockerfile`: Container definition

### Discovery Service (`discovery-service/`)
- `src/main/java/com/example/testProj/model/Service.java`: Data model for registered services
- `src/main/java/com/example/testProj/service/ServiceRegistry.java`: Stores and retrieves services
- `src/main/java/com/example/testProj/controller/DiscoveryController.java`: REST API endpoints
- `pom.xml`: Maven dependencies (includes springdoc-openapi for automatic OpenAPI spec generation)
- `Dockerfile`: Container definition

### Kubernetes Manifests (`k8s/`)
- `service-a-deployment.yaml`: How to run Service A (1 replica, environment vars, health probes)
- `service-a-service.yaml`: How to expose Service A (DNS name, internal IP)
- `service-b-deployment.yaml`: How to run Service B
- `service-b-service.yaml`: How to expose Service B
- `discovery-service-deployment.yaml`: How to run Discovery Service
- `discovery-service-service.yaml`: How to expose Discovery Service

## Key Learning Points

### Why No Hardcoding?

Services use `@Value("${env.var:default}")` to inject configuration. This means:
- Same code works in different environments
- No recompilation needed to change URLs
- Different environments (dev, staging, prod) can have different discovery service locations

### Reusable Registration

The `DiscoveryRegistration` component is included in both Service A and Service B identically. Any new service can include it with just environment variables:

```bash
-e APP_NAME=service-x
-e APP_SERVICE_URL=http://service-x:8080
-e DISCOVERY_SERVICE_URL=http://discovery-service:8080
```

No code duplication. This is the "onboarding SDK" — configuration-driven, not code-driven.

### Docker vs Kubernetes

| Aspect | Docker | Kubernetes |
|--------|--------|-----------|
| Service Discovery | Manual discovery service | CoreDNS (automatic) |
| Health Checks | Custom polling | Readiness/Liveness probes |
| Networking | Manual bridge network | Overlay network (automatic) |
| Container Management | Manual docker run commands | Declarative manifests |
| Restarts | Manual or restart policy | Automatic via Deployment |
| Configuration | Environment variables | Environment variables + ConfigMaps |

Both use the same registration code and environment variable pattern. The difference is what's automated.

## Testing the Full Flow

### In Docker:
```bash
# Service A makes request to Service B through discovery service
curl http://localhost:8082/call?name=John

# Check what services registered
curl http://localhost:8080/services
```

### In Kubernetes:
```bash
# Same request, but Kubernetes routing is transparent
kubectl port-forward svc/service-a 8080:8080
curl http://localhost:8080/call?name=John

# Check registered services (same API)
kubectl port-forward svc/discovery-service 8080:8080
curl http://localhost:8080/services
```

The code is identical. The infrastructure changes.

## Summary

This project demonstrates that successful microservice architectures require:

1. **Self-registration**: Services advertise themselves without hardcoding discovery service locations
2. **Health monitoring**: The system must know which services are healthy
3. **API metadata**: Clients must discover not just that a service exists, but how to use it
4. **Reusable patterns**: Registration should be a framework, not repeated code

Docker makes these patterns explicit. Kubernetes automates some of them natively (discovery, health), while still supporting explicit patterns (registration, metadata) when needed.
