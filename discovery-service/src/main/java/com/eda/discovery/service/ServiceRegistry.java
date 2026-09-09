package com.eda.discovery.service;

import com.eda.discovery.config.HealthCheckConfig;
import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.metrics.HealthMetrics;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.model.ServiceStatus;
import com.eda.discovery.repository.ServiceRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The service registry, backed by Redis.
 *
 * <p>Redis is the single source of truth for all three StatefulSet replicas: a
 * {@code POST /register} landing on any pod is immediately visible to the others, any
 * replica can serve any read, and nothing registry-related is held in pod-local memory
 * or on the pod filesystem, which does not survive a restart.
 *
 * <p>Because every replica resolves every service, every observed status transition can
 * be applied and published — including one observed by the partition leader for a
 * service that registered against a different pod. A consumer replaying
 * {@code service-events} therefore sees each service's complete lifecycle.
 */
@Component
public class ServiceRegistry {

    /**
     * Mutex for the health-check cycle. Any replica may run a cycle; this only ensures
     * two replicas do not probe every service simultaneously. Distinct from
     * {@code LeaderElectionService} (long-lived global leadership) and from
     * {@code PartitionLeaderElectionService} (per-partition Watch ownership).
     */
    private static final String HEALTH_LOCK_KEY = "discovery:healthcheck:lock";

    @Autowired
    private HealthCheckConfig healthCheckConfig;

    @Autowired
    private ServiceEventPublisher eventPublisher;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private TopicNamingStrategy topicNaming;

    /**
     * Probe statistics, deliberately node-local: they describe what <em>this</em> replica
     * observed and are cheap, high-frequency counters that do not belong in shared state.
     * Concurrent because HTTP request threads and the scheduled health-check thread both
     * touch it.
     */
    private final Map<String, HealthMetrics> serviceMetrics = new ConcurrentHashMap<>();

    private RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        // Apply health-check.timeout-ms, so a hung service cannot hold the health-check
        // thread for the platform default timeout.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(healthCheckConfig.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(healthCheckConfig.getTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
        System.out.println("ServiceRegistry initialized (Redis-backed) with config: " + healthCheckConfig);
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Upsert by name. Idempotent: re-registering an existing service updates it in place
     * rather than duplicating it, whichever replica handles the request.
     */
    public void register(Service service) {
        Service existing = serviceRepository.findById(service.getName()).orElse(null);
        if (existing != null) {
            // Carry forward everything the registering service does not know about itself.
            service.setCreatedAt(existing.getCreatedAt());
            service.setStatusGeneration(existing.getStatusGeneration());
            service.setCapabilities(existing.getCapabilities());
        }

        service.setStatus(ServiceStatus.UNKNOWN);
        service.resetFailures();
        service.setInputTopic(topicNaming.inputTopicFor(service.getName()));
        service.setCompensationTopic(topicNaming.compensationTopicFor(service.getName()));
        service.setUpdatedAt(LocalDateTime.now());

        serviceRepository.save(service);
        serviceMetrics.put(service.getName(), new HealthMetrics(service.getName()));

        System.out.println("Service registered: " + service.getName()
                + " (input=" + service.getInputTopic()
                + ", compensation=" + service.getCompensationTopic() + ")");

        eventPublisher.publish(ServiceEvent.from(ServiceEvent.Type.SERVICE_REGISTERED, service));
    }

    /**
     * Removes the service outright rather than flipping its status, so it stops appearing
     * in {@code GET /services} and {@code GET /services/{name}} returns 404. The emitted
     * SERVICE_DEREGISTERED is the explicit removal instruction a replaying consumer needs
     * in order to drop the entry from its own table.
     */
    public void deregister(String name) {
        Service service = serviceRepository.findById(name).orElse(null);
        if (service == null) return;

        serviceRepository.deleteById(name);
        serviceMetrics.remove(name);
        service.setStatus(ServiceStatus.UNAVAILABLE);

        System.out.println("Service deregistered: " + name);
        eventPublisher.publish(ServiceEvent.from(ServiceEvent.Type.SERVICE_DEREGISTERED, service));
    }

    // -------------------------------------------------------------------------
    // Reads — identical on every replica
    // -------------------------------------------------------------------------

    public List<Service> getAllServices() {
        List<Service> services = new ArrayList<>();
        // Redis secondary indexes can outlive the hash they point at, in which case
        // findAll() yields nulls. Filtering here keeps that leak out of every caller.
        serviceRepository.findAll().forEach(service -> {
            if (service != null) services.add(service);
        });
        return services;
    }

    public Service getServiceByName(String name) {
        return serviceRepository.findById(name).orElse(null);
    }

    public HealthMetrics getMetricsForService(String serviceName) {
        return serviceMetrics.get(serviceName);
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /** Applies a status update at the service's current generation (no fence advance). */
    public void updateServiceStatus(String name, String status) {
        Service service = serviceRepository.findById(name).orElse(null);
        if (service == null) return;
        applyStatus(service, status, service.getStatusGeneration());
    }

    /**
     * Applies a status transition, guarded by a fencing token.
     *
     * <p>{@code generation} is monotonic per partition and increments whenever partition
     * leadership moves. An update carrying a generation older than the one already
     * recorded comes from a leader that has since been deposed, and applying it would
     * resurrect a stale view — typically a late "unavailable" tearing down a route the
     * new leader has already reported healthy. Such updates are dropped.
     */
    public void updateServiceStatus(String name, String status, long generation) {
        Service service = serviceRepository.findById(name).orElse(null);
        if (service == null) return;
        applyStatus(service, status, generation);
    }

    private void applyStatus(Service service, String status, long generation) {
        if (generation < service.getStatusGeneration()) {
            System.out.println("[Registry] Dropping stale status for " + service.getName()
                    + " (gen=" + generation + " < " + service.getStatusGeneration() + ")");
            return;
        }
        // Don't republish an unchanged status: the Watch re-reports the same state on
        // every resync, and each publish is a record every consumer must process.
        if (status.equals(service.getStatus()) && generation == service.getStatusGeneration()) {
            return;
        }

        service.setStatus(status);
        service.setStatusGeneration(generation);
        serviceRepository.save(service);

        eventPublisher.publish(ServiceEvent.from(ServiceEvent.Type.STATUS_CHANGED, service));
    }

    // -------------------------------------------------------------------------
    // Health checking
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "#{@healthCheckConfig.getIntervalMs()}")
    public void healthCheck() {
        String token = UUID.randomUUID().toString();
        // TTL must outlive a full cycle, otherwise the lock expires mid-cycle and a
        // second replica starts probing in parallel.
        Duration ttl = Duration.ofMillis(Math.max(healthCheckConfig.getIntervalMs(), 1000L) * 3);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(HEALTH_LOCK_KEY, token, ttl);
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

    /**
     * Owner-checked release, so a replica whose lock already expired cannot delete the
     * successor's lock.
     *
     * <p>Known limitation: the check and the delete are two round trips, so a lock that
     * expires between them can still be deleted by its previous owner. The window is
     * bounded by one Redis round trip against a TTL of three health-check intervals.
     * {@code LeaderElectionService} shows the fully atomic form (a Lua compare-and-delete)
     * if this ever needs hardening.
     */
    private void releaseLock(String token) {
        Object current = redisTemplate.opsForValue().get(HEALTH_LOCK_KEY);
        if (token.equals(current)) {
            redisTemplate.delete(HEALTH_LOCK_KEY);
        }
    }

    private void checkServiceHealth(Service service) {
        String healthUrl = service.getUrl() + service.getHealthEndpoint();
        HealthMetrics metrics = serviceMetrics.computeIfAbsent(
                service.getName(), HealthMetrics::new);

        boolean success = false;
        long startTime = System.currentTimeMillis();
        int failuresBefore = service.getConsecutiveFailures();

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
                        return;
                    }
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        String newStatus;

        if (success) {
            metrics.recordSuccess(duration);
            service.resetFailures();
            newStatus = ServiceStatus.HEALTHY;
        } else {
            metrics.recordFailure();
            service.incrementFailure();

            if (service.getConsecutiveFailures() >= healthCheckConfig.getFailureThreshold()) {
                newStatus = ServiceStatus.UNAVAILABLE;
                System.out.println("Service marked UNAVAILABLE: " + service.getName()
                        + " (failures: " + service.getConsecutiveFailures() + ")");
            } else {
                newStatus = ServiceStatus.NOT_READY;
                System.out.println("Service not ready: " + service.getName()
                        + " (failures: " + service.getConsecutiveFailures() + "/"
                        + healthCheckConfig.getFailureThreshold() + ") | Metrics: " + metrics);
            }
        }

        // Probing is advisory: it re-uses the service's current generation so it never
        // outranks the Kubernetes Watch, which is authoritative for pod-level state.
        boolean statusChanged = !newStatus.equals(service.getStatus());
        applyStatus(service, newStatus, service.getStatusGeneration());

        // The failure counter lives in Redis and every cycle loads a fresh copy, so an
        // increment that applyStatus did not persist has to be written here. Otherwise
        // the counter never climbs to the threshold and a dead service is never marked
        // UNAVAILABLE.
        if (!statusChanged && service.getConsecutiveFailures() != failuresBefore) {
            serviceRepository.save(service);
        }
    }
}
