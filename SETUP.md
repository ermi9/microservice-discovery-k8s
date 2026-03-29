# Setup and Deployment Instructions

## Prerequisites

### For Docker
- Docker installed and running
- Docker daemon accessible

### For Kubernetes
- Kubernetes cluster (minikube or real cluster)
- kubectl installed and configured
- Images available (locally or in registry)

## Building the Project

### Step 1: Build Maven Projects

Each service is a Spring Boot Maven project. Build all three:

```bash
cd service-a
mvn clean package -DskipTests
cd ../service-b
mvn clean package -DskipTests
cd ../discovery-service
mvn clean package -DskipTests
cd ..
```

After building, each service will have a JAR file in `target/` directory.

### Step 2: Build Docker Images

```bash
docker build -t service-a:latest ./service-a/
docker build -t service-b:latest ./service-b/
docker build -t discovery-service:v2 ./discovery-service/
```

Verify:
```bash
docker images | grep service
```

## Deploying to Docker

### Step 1: Create Network

```bash
docker network create discovery-network
```

### Step 2: Start Discovery Service First

```bash
docker run --network discovery-network \
  --name discovery-service \
  -p 8080:8080 \
  -d \
  discovery-service:v2
```

### Step 3: Start Service B

```bash
docker run --network discovery-network \
  --name service-b \
  -p 8081:8080 \
  -e APP_NAME=service-b \
  -e APP_SERVICE_URL=http://service-b:8080 \
  -e DISCOVERY_SERVICE_URL=http://discovery-service:8080 \
  -d \
  service-b:latest
```

### Step 4: Start Service A

```bash
docker run --network discovery-network \
  --name service-a \
  -p 8082:8080 \
  -e APP_NAME=service-a \
  -e APP_SERVICE_URL=http://service-a:8080 \
  -e SERVICE_B_URL=http://service-b:8080 \
  -e DISCOVERY_SERVICE_URL=http://discovery-service:8080 \
  -d \
  service-a:latest
```

### Step 5: Verify Containers Are Running

```bash
docker ps | grep service
```

### Step 6: Test the System

```bash
# Service A calling Service B
curl http://localhost:8082/call?name=test

# Check registered services
curl http://localhost:8080/services

# Check health of individual services
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8082/health
```

### Step 7: View Logs

```bash
docker logs discovery-service
docker logs service-b
docker logs service-a
```

### Cleanup Docker

```bash
docker stop discovery-service service-b service-a
docker rm discovery-service service-b service-a
docker network rm discovery-network
```

## Deploying to Kubernetes

### Step 1: Load Images into Kubernetes

If using minikube:

```bash
minikube image load service-a:latest
minikube image load service-b:latest
minikube image load discovery-service:v2
```

If using a real cluster with a registry:

```bash
# Tag images for registry
docker tag service-a:latest your-registry/service-a:latest
docker tag service-b:latest your-registry/service-b:latest
docker tag discovery-service:v2 your-registry/discovery-service:v2

# Push to registry
docker push your-registry/service-a:latest
docker push your-registry/service-b:latest
docker push your-registry/discovery-service:v2

# Update manifest images
# Edit k8s/*.yaml files to reference your-registry/...
```

### Step 2: Create Namespace (Optional but Recommended)

```bash
kubectl create namespace microservices
```

If you create a namespace, add this to each manifest file in the metadata section:

```yaml
metadata:
  namespace: microservices
  name: service-a
```

### Step 3: Deploy All Manifests

```bash
kubectl apply -f k8s/
```

Or if using a specific namespace:

```bash
kubectl apply -f k8s/ -n microservices
```

### Step 4: Verify Deployments

```bash
# Check pods
kubectl get pods

# Check services
kubectl get svc

# Describe a specific pod (if there's an issue)
kubectl describe pod <pod-name>

# Check logs
kubectl logs <pod-name>
```

### Step 5: Wait for Readiness

Pods might show "0/1 Ready" initially. Wait for them to become "1/1 Running":

```bash
kubectl get pods --watch
```

Press Ctrl+C when all are Running.

If a pod is stuck:
```bash
kubectl describe pod <pod-name>
# Look at "Events" section to see why
```

### Step 6: Test the System

```bash
# Port-forward to Service A
kubectl port-forward svc/service-a 8080:8080 &

# In another terminal, test
curl http://localhost:8080/call?name=test

# Port-forward to Discovery Service
kubectl port-forward svc/discovery-service 8080:8080 &
curl http://localhost:8080/services

# Kill port-forwards
kill %1 %2
```

### Step 7: View Logs

```bash
kubectl logs deployment/service-a
kubectl logs deployment/service-b
kubectl logs deployment/discovery-service

# Or follow logs in real-time
kubectl logs -f deployment/service-a
```

### Step 8: Monitor Health Probes

```bash
kubectl get pods -o wide
# Shows Pod IPs

kubectl describe pod <service-a-pod-name>
# Shows probe results in Events
```

### Cleanup Kubernetes

```bash
# Delete all resources in the manifests
kubectl delete -f k8s/

# Or delete by label
kubectl delete deployment,svc -l app in (service-a,service-b,discovery-service)

# Or delete the entire namespace
kubectl delete namespace microservices
```

## Troubleshooting

### Docker Issues

**Service A can't reach Service B:**
- Make sure all containers are on the same network: `docker network inspect discovery-network`
- Check SERVICE_B_URL environment variable: `docker inspect service-a | grep SERVICE_B_URL`
- Try curling directly from the container: `docker exec service-a curl http://service-b:8080/health`

**Registration failing:**
- Check discovery service is running: `docker ps`
- Check logs: `docker logs discovery-service`
- Try registering manually: `curl -X POST http://localhost:8080/register -H "Content-Type: application/json" -d '{"name":"test","url":"http://test:8080","openapiUrl":"..."}'`

### Kubernetes Issues

**Pod stuck in ImagePullBackOff:**
- Make sure images are loaded: `kubectl get nodes -o wide` and check image availability
- If using minikube: `minikube image load service-a:latest`
- If using a registry: check image name in manifests matches registry

**Pod stuck in Pending:**
- Check resources: `kubectl describe pod <pod-name>`
- Check node capacity: `kubectl top nodes`

**Readiness probe failing:**
- Check pod logs: `kubectl logs <pod-name>`
- Pod might still be starting. Wait a bit.
- Check environment variables: `kubectl exec <pod-name> -- env | grep DISCOVERY`

**Service can't reach each other:**
- Check DNS resolution: `kubectl exec <pod-name> -- nslookup service-a`
- Check Services exist: `kubectl get svc`
- Check pod labels match Service selector: `kubectl get pods --show-labels`

**Deployment didn't update:**
- Check for old pods: `kubectl get pods`
- Force rollout: `kubectl rollout restart deployment/service-a`
- Check rollout status: `kubectl rollout status deployment/service-a`

## Environment Variables Reference

### Service A and Service B

```
APP_NAME=service-a                    # Name to register with discovery service
APP_SERVICE_URL=http://service-a:8080 # Own URL (for registration)
SERVICE_B_URL=http://service-b:8080   # Service B URL (for calling it, in Service A only)
DISCOVERY_SERVICE_URL=http://discovery-service:8080  # Discovery service URL
```

### Discovery Service

```
REGISTRY_FILE_PATH=/app/data/registry.json  # Where to store service list
```

## Verifying Everything Works

A successful deployment should:

1. All pods/containers running and healthy
2. Service A can call Service B: `curl .../call?name=test` returns data from Service B
3. Discovery service has registered all services: `curl .../services` returns a list
4. Each service responds to /health with "OK"
5. Kubernetes probes showing successful checks (in `kubectl describe pod`)

If all these work, the system is functioning correctly.
