package com.eda.discovery.service;

import com.eda.discovery.config.HealthCheckConfig;
import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.model.ServiceStatus;
import com.eda.discovery.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Generation fencing, producer side.
 *
 * <p>The mirror of the gateway's fencing test. Both ends of the stream must reject a
 * stale generation: the registry so it never writes a superseded status to Redis or
 * publishes it, the consumer so it never applies one it receives anyway.
 *
 * <p>Scope: the stale generation is synthesised rather than produced by a real leadership
 * move, so this covers the duplicate/reorder fault model. Behaviour under a real
 * partition-leader failover is cluster-only and not exercised here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistryFencingTest {

    @Mock private ServiceRepository serviceRepository;
    @Mock private HealthCheckConfig healthCheckConfig;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ServiceEventPublisher eventPublisher;
    @Mock private TopicNamingStrategy topicNaming;

    @InjectMocks private ServiceRegistry serviceRegistry;

    private Service stored;

    @BeforeEach
    void setUp() {
        stored = new Service("svc", "http://svc:8080", "http://svc:8080/v3/api-docs");
        stored.setStatus(ServiceStatus.HEALTHY);
        stored.setStatusGeneration(5);
        when(serviceRepository.findById("svc")).thenReturn(Optional.of(stored));
        when(serviceRepository.save(any(Service.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a status at a lower generation is neither persisted nor published")
    void staleIsDroppedEntirely() {
        serviceRegistry.updateServiceStatus("svc", ServiceStatus.UNAVAILABLE, 3);

        verify(serviceRepository, never()).save(any(Service.class));
        verify(eventPublisher, never()).publish(any(ServiceEvent.class));
        assertThat(stored.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(stored.getStatusGeneration()).isEqualTo(5);
    }

    @Test
    @DisplayName("a status at a higher generation is applied and published")
    void newerIsApplied() {
        serviceRegistry.updateServiceStatus("svc", ServiceStatus.UNAVAILABLE, 6);

        verify(serviceRepository).save(stored);
        ArgumentCaptor<ServiceEvent> captor = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(ServiceEvent.Type.STATUS_CHANGED);
        assertThat(captor.getValue().getStatus()).isEqualTo(ServiceStatus.UNAVAILABLE);
        assertThat(captor.getValue().getGeneration()).isEqualTo(6);
        assertThat(stored.getStatusGeneration()).isEqualTo(6);
    }

    @Test
    @DisplayName("an unchanged status at the same generation publishes nothing (no event storm)")
    void unchangedIsNotRepublished() {
        // The Watch re-reports the same state on every resync; each publish is a record
        // every downstream consumer must process.
        serviceRegistry.updateServiceStatus("svc", ServiceStatus.HEALTHY, 5);

        verify(serviceRepository, never()).save(any(Service.class));
        verify(eventPublisher, never()).publish(any(ServiceEvent.class));
    }

    @Test
    @DisplayName("a changed status at the same generation IS applied")
    void changedAtSameGenerationIsApplied() {
        // Same leader, genuinely new observation — must not be mistaken for a duplicate.
        serviceRegistry.updateServiceStatus("svc", ServiceStatus.NOT_READY, 5);

        verify(serviceRepository).save(stored);
        verify(eventPublisher).publish(any(ServiceEvent.class));
        assertThat(stored.getStatus()).isEqualTo(ServiceStatus.NOT_READY);
    }

    @Test
    @DisplayName("the advisory (no-generation) overload cannot advance the fence")
    void advisoryOverloadDoesNotAdvanceFence() {
        // The HTTP probe reuses the current generation precisely so it can never
        // outrank the Kubernetes Watch.
        serviceRegistry.updateServiceStatus("svc", ServiceStatus.NOT_READY);

        assertThat(stored.getStatusGeneration())
                .as("probe must not bump the generation")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("a status update for an unknown service is a no-op, not an NPE")
    void unknownServiceIsNoOp() {
        when(serviceRepository.findById("ghost")).thenReturn(Optional.empty());

        serviceRegistry.updateServiceStatus("ghost", ServiceStatus.HEALTHY, 1);

        verify(serviceRepository, never()).save(any(Service.class));
        verify(eventPublisher, never()).publish(any(ServiceEvent.class));
    }
}
