package com.example.testProj.service;
import com.example.testProj.model.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class ServiceRegistry {
    
    @Value("${registry.file.path:./data/registry.json}")
    private String registryFilePath;
    
    private List<Service> services = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        loadFromFile();
    }

    
    public void register(Service service) {
        // Check if service already exists
        services.removeIf(s -> s.getName().equals(service.getName()));
        
        // Add new service
        service.setStatus("unknown");
        service.setLastHeartbeat(LocalDateTime.now());
        services.add(service);
        
        // Save to file
        saveToFile();
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
    
    @Scheduled(fixedRate = 15000)
    public void healthCheck() {
        for (Service service : services) {
            try {
                String healthUrl = service.getUrl() + "/health";
                restTemplate.getForObject(healthUrl, String.class);
                service.setStatus("healthy");
                service.setLastHeartbeat(LocalDateTime.now());
            } catch (Exception e) {
                service.setStatus("unhealthy");
            }
        }
        saveToFile();
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