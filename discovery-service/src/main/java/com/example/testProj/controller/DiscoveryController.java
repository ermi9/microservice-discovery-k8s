package com.example.testProj.controller;

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
        return ResponseEntity.ok(serviceRegistry.getAllServices());
    }
    
    @GetMapping("/services/{name}")
    public ResponseEntity<?> getServiceByName(@PathVariable String name) {
        Service service = serviceRegistry.getServiceByName(name);
        if (service == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service);
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
