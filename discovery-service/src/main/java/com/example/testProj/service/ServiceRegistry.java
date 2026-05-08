package com.example.testProj.service;

import com.example.testProj.config.HealthCheckConfig;
import com.example.testProj.model.Service;
import com.example.testProj.repository.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ServiceRegistry {

    private static final String HEALTH_CHECK_LOCK_KEY = "discovery:health-check-lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private HealthCheckConfig healthCheckConfig;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        System.out.println("ServiceRegistry initialized with config: " + healthCheckConfig);
    }

    public void register(Service service) {
        Optional<Service> existing = serviceRepository.findById(service.getName());

        if (existing.isPresent()) {
            Service existingService = existing.get();
            existingService.setUrl(service.getUrl());
            existingService.setOpenapiUrl(service.getOpenapiUrl());
            existingService.setStatus("unknown");
            existingService.resetFailures();
            serviceRepository.save(existingService);
            System.out.println("Service updated: " + service.getName());
        } else {
            service.setStatus("unknown");
            service.resetFailures();
            serviceRepository.save(service);
            System.out.println("Service registered: " + service.getName());
        }
    }

    public List<Service> getAllServices() {
        Iterable<Service> iterable = serviceRepository.findAll();
        List<Service> services = new ArrayList<>();

        // Filter out nulls caused by orphaned Redis indexes
        for (Service service : iterable) {
            if (service != null) {
                services.add(service);
            }
        }
        return services;
    }

    public Service getServiceByName(String name) {
        Optional<Service> service = serviceRepository.findById(name);
        return service.orElse(null);
    }

    /**
     * Distributed health check: only the replica that acquires the Redis lock runs
     * the check cycle. The 60-second TTL ensures the lock expires if the holder crashes
     * before releasing it, so another replica can pick it up on the next interval.
     */
    @Scheduled(fixedDelayString = "#{@healthCheckConfig.getIntervalMs()}")
    public void healthCheck() {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(HEALTH_CHECK_LOCK_KEY, lockValue, LOCK_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            return; // Another replica already holds the lock
        }

        try {
            List<Service> services = getAllServices();
            for (Service service : services) {
                checkServiceHealth(service);
            }
        } finally {
            // Only release if we still own the lock (guards against TTL expiry + re-acquisition)
            Object current = redisTemplate.opsForValue().get(HEALTH_CHECK_LOCK_KEY);
            if (lockValue.equals(String.valueOf(current))) {
                redisTemplate.delete(HEALTH_CHECK_LOCK_KEY);
            }
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

        serviceRepository.save(service);
    }
}
