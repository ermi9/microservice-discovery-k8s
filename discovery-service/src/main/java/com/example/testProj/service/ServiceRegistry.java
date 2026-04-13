package com.example.testProj.service;

import com.example.testProj.model.Service;
import com.example.testProj.config.HealthCheckConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import java.io.IOException;
import java.util.*;

@Component
public class ServiceRegistry {
    
    @Value("${registry.file.path:./data/registry.json}")
    private String registryFilePath;
    
    @Autowired
    private HealthCheckConfig healthCheckConfig;
    
    private List<Service> services = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void init() {
        loadFromFile();
        System.out.println("ServiceRegistry initialized with config: " + healthCheckConfig);
    }
    
    public void register(Service service) {
        services.removeIf(s -> s.getName().equals(service.getName()));
        service.setStatus("unknown");
        service.resetFailures();
        services.add(service);
        saveToFile();
        System.out.println("Service registered: " + service.getName());
    }
    
    public List<Service> getAllServices() {
        return new ArrayList<>(services);
    }
    
    public Service getServiceByName(String name) {
        return services.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
    
    @Scheduled(fixedDelayString = "#{@healthCheckConfig.getIntervalMs()}")
    public void healthCheck() {
        for (Service service : services) {
            checkServiceHealth(service);
        }
        saveToFile();
    }
    
    private void checkServiceHealth(Service service) {
        String healthUrl = service.getUrl() + service.getHealthEndpoint();
        
        boolean success = false;
        for (int attempt = 0; attempt <= healthCheckConfig.getMaxRetries(); attempt++) {
            try {
                restTemplate.getForObject(healthUrl, String.class);
                success = true;
                break;
            } catch (RestClientException e) {
                if (attempt < healthCheckConfig.getMaxRetries()) {
                    System.out.println("Health check retry " + (attempt + 1) + " for " + service.getName());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        if (success) {
            service.resetFailures();
            service.setStatus("healthy");
        } else {
            service.incrementFailure();
            
            if (service.getConsecutiveFailures() >= healthCheckConfig.getFailureThreshold()) {
                service.setStatus("unhealthy");
                System.out.println("Service marked UNHEALTHY: " + service.getName() + 
                    " (failures: " + service.getConsecutiveFailures() + ")");
            } else {
                service.setStatus("degraded");
                System.out.println("Service degraded: " + service.getName() + 
                    " (failures: " + service.getConsecutiveFailures() + "/" + 
                    healthCheckConfig.getFailureThreshold() + ")");
            }
        }
    }
    
    private void saveToFile() {
        try {
            File file = new File(registryFilePath);
            file.getParentFile().mkdirs();
            
            Map<String, Object> registry = new HashMap<>();
            registry.put("services", services);
            
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(file, registry);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void loadFromFile() {
        try {
            File file = new File(registryFilePath);
            if (!file.exists()) {
                services = new ArrayList<>();
                return;
            }
            
            Map<String, Object> registry = objectMapper.readValue(file, Map.class);
            List<Map<String, Object>> servicesList = (List<Map<String, Object>>) registry.get("services");
            
            services = new ArrayList<>();
            for (Map<String, Object> svc : servicesList) {
                Service s = new Service();
                s.setName((String) svc.get("name"));
                s.setUrl((String) svc.get("url"));
                s.setOpenapiUrl((String) svc.get("openapiUrl"));
                s.setStatus((String) svc.get("status"));
                services.add(s);
            }
        } catch (IOException e) {
            services = new ArrayList<>();
        }
    }
}
