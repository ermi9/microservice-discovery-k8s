# K8s Service Discovery Framework - Architecture

## System Components

The framework consists of these key components:

### 1. DiscoveryController (REST API)
- `POST /register` - Services register on startup
- `GET /services` - Return all services with pod info
- `GET /services/{name}` - Get specific service details
- `GET /health` - Health probe for K8s liveness

### 2. ServiceRegistry (Core Logic)
- Maintains in-memory service list
- Runs health checks every 15 seconds (configurable)
- Tracks consecutive failures per service
- Persists registry to disk
- Uses HealthCheckConfig for all settings

### 3. HealthCheckConfig (Configuration)
- Reads from `application.properties`
- Configurable: interval, retries, thresholds, timeout
- Injected into ServiceRegistry via Spring

### 4. KubernetesDiscoveryService (K8s Integration)
- Queries K8s API for pod information
- Uses service account token for authentication
- Extracts: pod name, IP, status, readiness

### 5. KubernetesPollingService (Scheduler)
- Runs every 15 seconds
- Calls KubernetesDiscoveryService
- Attaches pod info to services

## Data Flow

### Registration (On Service Startup)
Service starts
→ DiscoveryRegistration listener fires
→ POST /register {name, url, openapiUrl}
→ ServiceRegistry.register()
→ Store in-memory + persist to disk

### Health Check (Every 15 Seconds)
@Scheduled timer fires
→ For each service:
→ GET {service.url}/health
→ If fails: retry 2 times with 500ms delay
→ Increment/reset consecutive failures
→ Mark status: healthy/degraded/unhealthy

### Pod Discovery (Every 15 Seconds)
KubernetesPollingService runs
→ GET /api/v1/namespaces/default/pods?labelSelector=app={serviceName}
→ Extract: name, IP, phase, readiness status
→ Attach to service object

## Key Design Patterns

### 1. Configuration-Driven
All behavior is externalized to `application.properties`:
- No hardcoded timeouts
- Easy to adjust per environment
- Different scenarios: dev, prod, critical

### 2. Resilient Health Checks
- Retry logic handles transient failures
- Failure threshold prevents flapping
- Only mark unhealthy after N consecutive failures

### 3. K8s Native
- Uses service account token (auto-mounted)
- Queries official K8s API
- Pod discovery built-in

### 4. In-Memory + Persistent
- Fast in-memory reads
- Registry persisted to disk
- Survives restarts

## Class Responsibilities

| Class | Responsibility |
|-------|---|
| `HealthCheckConfig` | Read configuration from properties |
| `Service` | Data model + failure counter |
| `ServiceRegistry` | Core registry logic, health checks, persistence |
| `KubernetesDiscoveryService` | Query K8s API for pod info |
| `KubernetesPollingService` | Scheduled pod discovery task |
| `DiscoveryController` | REST API endpoints |

## Extension Points

**Add custom health endpoints:**
```java
// Service model already supports this:
private String healthEndpoint = "/health";
public String getHealthEndpoint() { ... }
```

**Add metrics/observability:**


**Add service dependencies:**
```java
// Service model could store: dependsOn: [service-b]
// Validate dependency chains on registration
```

