package com.example.testProj;

import com.example.testProj.config.HealthCheckConfig;
import com.example.testProj.model.Service;
import com.example.testProj.repository.ServiceRepository;
import com.example.testProj.service.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Consistency tests for the discovery-service when scaled to 3 replicas.
 *
 * These tests verify three key properties required for horizontal scalability:
 *   1. Only one replica runs the health-check cycle at a time (distributed lock).
 *   2. Service registration is idempotent regardless of which replica handles it.
 *   3. Null entries from orphaned Redis indexes never reach callers.
 */
@ExtendWith(MockitoExtension.class)
class ConsistencyTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private HealthCheckConfig healthCheckConfig;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private ServiceRegistry serviceRegistry;

    // -------------------------------------------------------------------------
    // Test 1 – Distributed lock: only one of 3 replicas runs health checks
    // -------------------------------------------------------------------------

    @Test
    void only_one_replica_runs_health_checks_when_three_compete_for_lock() {
        /*
         * Simulate the first replica acquiring the lock (returns true) and the
         * other two finding it already held (returns false). All three call
         * healthCheck() sequentially, as they would on the same scheduler tick
         * across the cluster.
         */
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        AtomicBoolean lockHeld = new AtomicBoolean(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> lockHeld.compareAndSet(false, true));

        when(serviceRepository.findAll()).thenReturn(Collections.emptyList());

        // Replica 1 acquires the lock → runs checks
        serviceRegistry.healthCheck();
        // Replica 2 – lock is held → skips
        serviceRegistry.healthCheck();
        // Replica 3 – lock is held → skips
        serviceRegistry.healthCheck();

        // findAll() must have been called exactly once (by the lock holder)
        verify(serviceRepository, times(1)).findAll();
    }

    // -------------------------------------------------------------------------
    // Test 2 – Lock release: holder releases the lock after finishing
    // -------------------------------------------------------------------------

    @Test
    void lock_holder_releases_lock_after_health_check_cycle() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<String> lockValueCaptor = ArgumentCaptor.forClass(String.class);

        when(valueOps.setIfAbsent(anyString(), lockValueCaptor.capture(), any(Duration.class)))
                .thenReturn(true);
        when(serviceRepository.findAll()).thenReturn(Collections.emptyList());
        // Return the same UUID that was stored so the ownership check passes
        doAnswer(inv -> lockValueCaptor.getValue()).when(valueOps).get(anyString());

        serviceRegistry.healthCheck();

        verify(redisTemplate, times(1)).delete(anyString());
    }

    // -------------------------------------------------------------------------
    // Test 3 – Lock miss: replica that cannot acquire lock never touches data
    // -------------------------------------------------------------------------

    @Test
    void replica_skips_health_checks_when_lock_not_acquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        serviceRegistry.healthCheck();

        verify(serviceRepository, never()).findAll();
        verify(redisTemplate, never()).delete(anyString());
    }

    // -------------------------------------------------------------------------
    // Test 4 – Idempotent registration: same service registered from 3 replicas
    // -------------------------------------------------------------------------

    @Test
    void registration_is_idempotent_when_same_service_registered_from_three_replicas() {
        Service service = new Service("service-a", "http://service-a:8080",
                "http://service-a:8080/v3/api-docs");

        // Simulate the service already existing (as the second and third replica
        // would see after the first one wrote it)
        when(serviceRepository.findById("service-a")).thenReturn(Optional.of(service));
        when(serviceRepository.save(any(Service.class))).thenReturn(service);

        // All three replicas attempt to register the same service
        serviceRegistry.register(service);
        serviceRegistry.register(service);
        serviceRegistry.register(service);

        // Each call must update (not duplicate) the entry
        verify(serviceRepository, times(3)).findById("service-a");
        verify(serviceRepository, times(3)).save(any(Service.class));
    }

    // -------------------------------------------------------------------------
    // Test 5 – Consistent reads: null orphaned entries are filtered out
    // -------------------------------------------------------------------------

    @Test
    void getAllServices_filters_null_entries_from_orphaned_redis_indexes() {
        Service serviceA = new Service("service-a", "http://service-a:8080",
                "http://service-a:8080/v3/api-docs");
        Service serviceB = new Service("service-b", "http://service-b:8080",
                "http://service-b:8080/v3/api-docs");

        // Redis can return nulls for keys that exist in the secondary index
        // but whose hash has expired (orphaned index entries).
        // List.of() rejects nulls, so we use ArrayList here intentionally.
        List<Service> withOrphans = new ArrayList<>();
        withOrphans.add(serviceA);
        withOrphans.add(null);
        withOrphans.add(serviceB);
        when(serviceRepository.findAll()).thenReturn(withOrphans);

        List<Service> result = serviceRegistry.getAllServices();

        assertThat(result).hasSize(2);
        assertThat(result).doesNotContainNull();
        assertThat(result).extracting(Service::getName)
                .containsExactlyInAnyOrder("service-a", "service-b");
    }
}
