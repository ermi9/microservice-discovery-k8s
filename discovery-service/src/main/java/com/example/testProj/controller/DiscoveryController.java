package com.example.testProj.controller;

import com.example.testProj.kubernetes.KubernetesDiscoveryService;
import com.example.testProj.model.Service;
import com.example.testProj.service.ServiceRegistry;
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
        
        return ResponseEntity.ok(Map.of("message", "Service registered successfully", "service", name));
    }
    
    @GetMapping("/services")
    public ResponseEntity<List<Service>> getAllServices() {
        List<Service> services = serviceRegistry.getAllServices();
        
        for (Service service : services) {
            Map<String, Object> podInfo = findPodForService(service.getName());
            service.setPod(podInfo);
        }
        return ResponseEntity.ok(services);
    }
    
    //now this endpoint will return the service details along with the associated pod information if available
    @GetMapping("/services/{name}")
    public ResponseEntity<?> getServiceByName(@PathVariable String name) {
        Service service = serviceRegistry.getServiceByName(name);
        
        if (service == null) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> podInfo = findPodForService(name);
        service.setPod(podInfo);
        return ResponseEntity.ok(service);
    }
    
    // This method tries to find a pod associated with the given service name by looking for pods with a label "app=serviceName"
    private Map<String, Object> findPodForService(String serviceName) {
        try {
            String labelSelector = "app=" + serviceName;
            List<Map<String, Object>> pods = kubernetesDiscoveryService.getPodsByLabel("default", labelSelector);
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
  //health of the discovery service  
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
