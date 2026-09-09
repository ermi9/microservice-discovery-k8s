package com.eda.discovery.controller;

import com.eda.discovery.kubernetes.KubernetesDiscoveryService;
import com.eda.discovery.model.Service;
import com.eda.discovery.service.ServiceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/")
public class DiscoveryController {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private KubernetesDiscoveryService kubernetesDiscoveryService;

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
