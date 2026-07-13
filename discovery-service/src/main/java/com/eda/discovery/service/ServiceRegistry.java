package com.eda.discovery.service;

import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.config.HealthCheckConfig;
import com.eda.discovery.metrics.HealthMetrics;
import com.eda.discovery.repository.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of known services. State is held in Redis (via {@link ServiceRepository}, a
 * Spring Data {@code @RedisHash} repository) so that all discovery-service replicas share
 * a single, consistent view of the service catalog and its health status.
 *
 * <p>Per-replica in-memory state is limited to {@link HealthMetrics}, which is pure
 * observability (latency/success counters) and intentionally not part of the shared,
 * authoritative state.
 */
@Component
public class ServiceRegistry {

    /** Advisory lock ensuring only one replica runs the HTTP health sweep per cycle. */
    private static final String HEALTHCHECK_LOCK_KEY = "discovery:healthcheck:lock";

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private HealthCheckConfig healthCheckConfig;

    @Autowired
    private ServiceEventPublisher eventPublisher;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Per-replica latency/success counters — observability only, not shared state.
    private final Map<String, HealthMetrics> serviceMetrics = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();

    public void register(Service service) {
        // Idempotent: same service registered from any replica converges to one entry.
        Optional<Service> existing = serviceRepository.findById(service.getName());
        Service toSave;
        if (existing.isPresent()) {
            toSave = existing.get();
            toSave.setUrl(service.getUrl());
            toSave.setOpenapiUrl(service.getOpenapiUrl());
        } else {
            service.setStatus("unknown");
            service.resetFailures();
            toSave = service;
        }
        serviceRepository.save(toSave);
        serviceMetrics.putIfAbsent(toSave.getName(), new HealthMetrics(toSave.getName()));

        System.out.println("Service registered: " + toSave.getName());
        ServiceEvent event = new ServiceEvent(
                ServiceEvent.Type.SERVICE_REGISTERED, toSave.getName(), toSave.getUrl(), toSave.getStatus());
        event.setOpenapiUrl(toSave.getOpenapiUrl());
        eventPublisher.publish(event);
    }

    public List<Service> getAllServices() {
        // findAll() can yield nulls for orphaned secondary-index entries whose hash expired.
        List<Service> result = new ArrayList<>();
        for (Service s : serviceRepository.findAll()) {
            if (s != null) {
                result.add(s);
            }
        }
        return result;
    }

    public Service getServiceByName(String name) {
        return serviceRepository.findById(name).orElse(null);
    }

    public void deregister(String name) {
        serviceRepository.findById(name).ifPresent(s -> {
            s.setStatus("unavailable");
            serviceRepository.save(s);
            System.out.println("Service deregistered: " + name);
            eventPublisher.publish(new ServiceEvent(
                    ServiceEvent.Type.SERVICE_DEREGISTERED, name, s.getUrl(), "unavailable"));
        });
    }

    public void updateServiceStatus(String name, String status) {
        updateServiceStatus(name, status, 0L);
    }

    public void updateServiceStatus(String name, String status, long generation) {
        serviceRepository.findById(name).ifPresent(s -> {
            s.setStatus(status);
            serviceRepository.save(s);
            ServiceEvent event = new ServiceEvent(
                    ServiceEvent.Type.STATUS_CHANGED, name, s.getUrl(), status);
            event.setGeneration(generation);
            eventPublisher.publish(event);
        });
    }

    public HealthMetrics getMetricsForService(String serviceName) {
        return serviceMetrics.get(serviceName);
    }

    /**
     * HTTP liveness sweep. Guarded by a per-cycle distributed lock so that only one replica
     * probes services on a given tick; results are written back to shared Redis. This is a
     * fallback cross-check — primary status tracking is the Kubernetes Watch stream.
     */
    @Scheduled(fixedDelayString = "#{@healthCheckConfig.getIntervalMs()}")
    public void healthCheck() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(HEALTHCHECK_LOCK_KEY, token, Duration.ofMillis(healthCheckConfig.getIntervalMs()));
        if (!Boolean.TRUE.equals(acquired)) {
            return; // another replica owns this cycle
        }
        try {
            for (Service service : getAllServices()) {
                checkServiceHealth(service);
            }
        } finally {
            releaseLock(token);
        }
    }

    // Release the lock only if we still own it. Non-atomic get+delete is sufficient for an
    // advisory lock backed by a TTL (a Lua CAS is used for the leadership locks that must be
    // strictly fenced — see LeaderElectionService).
    private void releaseLock(String token) {
        Object current = redisTemplate.opsForValue().get(HEALTHCHECK_LOCK_KEY);
        if (token.equals(current)) {
            redisTemplate.delete(HEALTHCHECK_LOCK_KEY);
        }
    }

    private void checkServiceHealth(Service service) {
        String healthUrl = service.getUrl() + service.getHealthEndpoint();
        HealthMetrics metrics = serviceMetrics.computeIfAbsent(service.getName(), HealthMetrics::new);
        String previousStatus = service.getStatus();

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

        long duration = System.currentTimeMillis() - startTime;

        if (success) {
            metrics.recordSuccess(duration);
            service.resetFailures();
            service.setStatus("healthy");
        } else {
            metrics.recordFailure();
            service.incrementFailure();
            if (service.getConsecutiveFailures() >= healthCheckConfig.getFailureThreshold()) {
                service.setStatus("unhealthy");
                System.out.println("Service marked UNHEALTHY: " + service.getName()
                        + " (failures: " + service.getConsecutiveFailures() + ")");
            } else {
                service.setStatus("degraded");
            }
        }

        // Persist to shared Redis so every replica sees the same status.
        serviceRepository.save(service);

        // Notify the gateway only when the status actually transitions.
        if (!service.getStatus().equals(previousStatus)) {
            eventPublisher.publish(new ServiceEvent(
                    ServiceEvent.Type.STATUS_CHANGED, service.getName(), service.getUrl(), service.getStatus()));
        }
    }
}
