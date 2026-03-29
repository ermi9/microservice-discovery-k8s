package com.example.testProj.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Component
public class DiscoveryRegistration {
    
    @Value("${app.name:unknown-service}")
    private String appName;
    
    @Value("${app.service.url:http://localhost:8080}")
    private String serviceUrl;
    
    @Value("${discovery.service.url:http://localhost:8080}")
    private String discoveryServiceUrl;
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    @EventListener(ApplicationReadyEvent.class)
    public void registerWithDiscoveryService() {
        try {
            Map<String, String> request = new HashMap<>();
            request.put("name", appName);
            request.put("url", serviceUrl);
            request.put("openapiUrl", serviceUrl + "/api/openapi.json");
            
            String registerUrl = discoveryServiceUrl + "/register";
            restTemplate.postForObject(registerUrl, request, Map.class);
            
            System.out.println("Successfully registered with discovery service: " + appName);
        } catch (Exception e) {
            System.out.println("Failed to register with discovery service: " + e.getMessage());
        }
    }
}