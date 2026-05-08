package com.example.testProj.service;

import com.example.testProj.config.HealthCheckConfig;
import com.example.testProj.model.Service;
import com.example.testProj.repository.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ServiceRegistry {
    
    @Autowired
    private ServiceRepository serviceRepository;
    
    @Autowired
    private HealthCheckConfig healthCheckConfig;
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void init() {
        System.out.println("ServiceRegistry initialized with config: " + healthCheckConfig);
    }
    
    public void register(Service service) {
        // Check if service already exists by name
        Optional<Service> existing = serviceRepository.findById(service.getName());
        
        if (existing.isPresent()) {
            // Update existing service
            Service existingService = existing.get();
            existingService.setUrl(service.getUrl());
            existingService.setOpenapiUrl(service.getOpenapiUrl());
            existingService.setStatus("unknown");
            existingService.resetFailures();
            serviceRepository.save(existingService);
            System.out.println("Service updated: " + service.getName());
        } else {
            // Create new service
            service.setStatus("unknown");
            service.resetFailures();
            serviceRepository.save(service);
            System.out.println("Service registered: " + service.getName());
        }
    }
    
    public List<Service> getAllServices() {
        // CrudRepository.findAll() returns Iterable, convert to List
        Iterable<Service> iterable = serviceRepository.findAll();
        List<Service> services = new ArrayList<>();
        iterable.forEach(services::add);
        return services;
    }
    
    public Service getServiceByName(String name) {
    //changed, to findbyId to directly look up in redis
        Optional<Service> service = serviceRepository.findById(name);
        return service.orElse(null);
    }
    
    @Scheduled(fixedDelayString = "#{@healthCheckConfig.getIntervalMs()}")
    public void healthCheck() {
        List<Service> services = getAllServices();
        for (Service service : services) {
            checkServiceHealth(service);
        }
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
        
        // Save updated service status to Redis
        serviceRepository.save(service);
    }
}
