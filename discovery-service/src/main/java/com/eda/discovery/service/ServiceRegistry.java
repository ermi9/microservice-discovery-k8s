package com.eda.discovery.service;

import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.config.HealthCheckConfig;
import com.eda.discovery.metrics.HealthMetrics;
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

    @Autowired
    private ServiceEventPublisher eventPublisher;

    @Autowired
    private LeaderElectionService leaderElectionService;

    private List<Service> services = new ArrayList<>();
    private Map<String, HealthMetrics> serviceMetrics = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void init() {
        loadFromFile();
        System.out.println("ServiceRegistry initialized with config: " + healthCheckConfig);
    }
    
    public void register(Service service) {
        // Check if service already exists
        services.removeIf(s -> s.getName().equals(service.getName()));
        
        // Add new service
        service.setStatus("unknown");
        service.resetFailures();
        services.add(service);
        
        // Create metrics for this service
        serviceMetrics.put(service.getName(), new HealthMetrics(service.getName()));
        
        saveToFile();
        System.out.println("Service registered: " + service.getName());
        ServiceEvent event = new ServiceEvent(
                ServiceEvent.Type.SERVICE_REGISTERED, service.getName(), service.getUrl(), service.getStatus());
        event.setOpenapiUrl(service.getOpenapiUrl());
        eventPublisher.publish(event);
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
    
    public void deregister(String name) {
        services.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .ifPresent(s -> {
                    s.setStatus("unavailable");
                    saveToFile();
                    System.out.println("Service deregistered: " + name);
                    eventPublisher.publish(new ServiceEvent(
                            ServiceEvent.Type.SERVICE_DEREGISTERED, name, s.getUrl(), "unavailable"));
                });
    }

    public void updateServiceStatus(String name, String status) {
        updateServiceStatus(name, status, 0L);
    }

    public void updateServiceStatus(String name, String status, long generation) {
        services.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .ifPresent(s -> {
                    s.setStatus(status);
                    saveToFile();
                    ServiceEvent event = new ServiceEvent(
                            ServiceEvent.Type.STATUS_CHANGED, name, s.getUrl(), status);
                    event.setGeneration(generation);
                    eventPublisher.publish(event);
                });
    }

    public HealthMetrics getMetricsForService(String serviceName) {
        return serviceMetrics.get(serviceName);
    }
    
    @Scheduled(fixedDelayString = "#{@healthCheckConfig.getIntervalMs()}")
    public void healthCheck() {
        if (!leaderElectionService.isLeader()) return;
        for (Service service : services) {
            checkServiceHealth(service);
        }
        saveToFile();
    }

    private void checkServiceHealth(Service service) {
        String healthUrl = service.getUrl() + service.getHealthEndpoint();
        HealthMetrics metrics = serviceMetrics.get(service.getName());
        
        if (metrics == null) {
            metrics = new HealthMetrics(service.getName());
            serviceMetrics.put(service.getName(), metrics);
        }
        
        // Retry loop
        boolean success = false;
        long startTime = System.currentTimeMillis();
        
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
        
        // Record metrics
        long duration = System.currentTimeMillis() - startTime;
        
        // Update status based on consecutive failures
        if (success) {
            metrics.recordSuccess(duration);
            service.resetFailures();
            service.setStatus("healthy");
        } else {
            metrics.recordFailure();
            service.incrementFailure();
            
            if (service.getConsecutiveFailures() >= healthCheckConfig.getFailureThreshold()) {
                service.setStatus("unhealthy");
                System.out.println("Service marked UNHEALTHY: " + service.getName() + 
                    " (failures: " + service.getConsecutiveFailures() + ")");
            } else {
                service.setStatus("degraded");
                System.out.println("Service degraded: " + service.getName() + 
                    " (failures: " + service.getConsecutiveFailures() + "/" + 
                    healthCheckConfig.getFailureThreshold() + ") | Metrics: " + metrics);
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
                
                // Create metrics for loaded service
                serviceMetrics.put(s.getName(), new HealthMetrics(s.getName()));
            }
        } catch (IOException e) {
            services = new ArrayList<>();
        }
    }
}
