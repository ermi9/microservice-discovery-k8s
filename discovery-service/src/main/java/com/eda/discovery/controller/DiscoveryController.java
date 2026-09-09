package com.eda.discovery.controller;

import com.eda.discovery.kubernetes.KubernetesDiscoveryService;
import com.eda.discovery.metrics.HealthMetrics;
import com.eda.discovery.model.Service;
import com.eda.discovery.service.CapabilityCatalogService;
import com.eda.discovery.service.ServiceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/")
public class DiscoveryController {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private KubernetesDiscoveryService kubernetesDiscoveryService;

    @Autowired
    private CapabilityCatalogService capabilityCatalog;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String url = request.get("url");
        String openapiUrl = request.get("openapiUrl");

        if (name == null || url == null || openapiUrl == null) {
            return ResponseEntity.badRequest().body("Missing required fields: name, url, openapiUrl");
        }

        Service service = new Service(name, url, openapiUrl);
        serviceRegistry.register(service);

        // Echo the derived Kafka destinations so a registering service can log/verify
        // where the platform expects it to consume from.
        return ResponseEntity.ok(Map.of(
                "message", "Service registered successfully",
                "service", name,
                "inputTopic", String.valueOf(service.getInputTopic()),
                "compensationTopic", String.valueOf(service.getCompensationTopic())));
    }

    @GetMapping("/services")
    public ResponseEntity<List<Service>> getAllServices() {
        List<Service> services = serviceRegistry.getAllServices();

        for (Service service : services) {
            service.setPod(findPodForService(service.getName()));
        }
        return ResponseEntity.ok(services);
    }

    @GetMapping("/services/{name}")
    public ResponseEntity<?> getServiceByName(@PathVariable String name) {
        Service service = serviceRegistry.getServiceByName(name);

        if (service == null) {
            return ResponseEntity.notFound().build();
        }

        service.setPod(findPodForService(name));
        return ResponseEntity.ok(service);
    }

    /**
     * Probe statistics this replica has collected for a service — check count, successes,
     * failures, success rate, last observed latency.
     *
     * <p>Node-local by design: the numbers describe what this replica observed.
     */
    @GetMapping("/services/{name}/metrics")
    public ResponseEntity<?> getServiceMetrics(@PathVariable String name) {
        if (serviceRegistry.getServiceByName(name) == null) {
            return ResponseEntity.notFound().build();
        }

        HealthMetrics metrics = serviceRegistry.getMetricsForService(name);
        if (metrics == null) {
            return ResponseEntity.ok(Map.of(
                    "service", name,
                    "message", "No probe has run on this replica yet"));
        }

        return ResponseEntity.ok(Map.of(
                "service", metrics.getServiceName(),
                "totalChecks", metrics.getTotalChecks(),
                "successCount", metrics.getSuccessCount(),
                "failureCount", metrics.getFailureCount(),
                "successRate", metrics.getSuccessRate(),
                "lastResponseMs", metrics.getLastResponseMs()));
    }

    /**
     * The operations a service actually exposes, parsed from its OpenAPI document.
     * Lets a caller validate a plan step against a real capability instead of assuming it.
     */
    @GetMapping("/services/{name}/capabilities")
    public ResponseEntity<?> getCapabilities(@PathVariable String name,
                                             @RequestParam(defaultValue = "false") boolean refresh) {
        Service service = serviceRegistry.getServiceByName(name);
        if (service == null) {
            return ResponseEntity.notFound().build();
        }

        Set<String> capabilities = refresh
                ? capabilityCatalog.refresh(name)
                : service.getCapabilities();

        return ResponseEntity.ok(Map.of(
                "service", name,
                "openapiUrl", String.valueOf(service.getOpenapiUrl()),
                "capabilities", capabilities));
    }

    /** Direct predicate for plan validation: does this service support this operation? */
    @GetMapping("/services/{name}/supports")
    public ResponseEntity<?> supports(@PathVariable String name, @RequestParam String operation) {
        if (serviceRegistry.getServiceByName(name) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "service", name,
                "operation", operation,
                "supported", capabilityCatalog.supports(name, operation)));
    }

    @DeleteMapping("/services/{name}")
    public ResponseEntity<?> deregister(@PathVariable String name) {
        if (serviceRegistry.getServiceByName(name) == null) {
            return ResponseEntity.notFound().build();
        }
        serviceRegistry.deregister(name);
        return ResponseEntity.ok(Map.of("message", "Service deregistered", "service", name));
    }

    /**
     * Liveness: the process is up and can serve HTTP. Deliberately dependency-free — a
     * Redis outage must not make Kubernetes kill and restart every discovery pod.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * Readiness: this replica can actually do its job.
     *
     * <p>Kept separate from {@code /health} and wired to the one dependency every request
     * path needs. A replica that cannot reach Redis cannot read the registry, so it
     * reports not-ready and the kubelet stops sending it traffic.
     */
    @GetMapping("/ready")
    public ResponseEntity<?> ready() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return ResponseEntity.ok(Map.of("status", "READY", "redis", "UP"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "NOT_READY", "redis", "DOWN", "reason", String.valueOf(e.getMessage())));
        }
    }

    /** Finds a Running pod labelled {@code app=<serviceName>}, or null. */
    private Map<String, Object> findPodForService(String serviceName) {
        try {
            List<Map<String, Object>> pods =
                    kubernetesDiscoveryService.getPodsByLabel("default", "app=" + serviceName);
            for (Map<String, Object> pod : pods) {
                if ("Running".equals(pod.get("status"))) {
                    return pod;
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("Error finding pod for service " + serviceName + ": " + e.getMessage());
            return null;
        }
    }
}
