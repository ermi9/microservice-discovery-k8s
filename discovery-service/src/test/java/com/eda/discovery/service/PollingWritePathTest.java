package com.eda.discovery.service;

import com.eda.discovery.kubernetes.KubernetesDiscoveryService;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceStatus;
import com.eda.discovery.repository.ServiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Single authoritative write path.
 *
 * <p>{@code KubernetesPollingService} must route every status change through
 * {@code ServiceRegistry} and never call {@code serviceRepository.save(...)} itself: a
 * direct write bypasses both the generation fence and the Kafka publish, so the poller
 * could overwrite a newer status from the Watch with no consumer hearing about it.
 *
 * <p>The assertion is deliberately negative — "never touches the repository" — because
 * what has to be excluded is an <em>extra</em> write path, not a wrong value.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PollingWritePathTest {

    @Mock private ServiceRegistry serviceRegistry;
    @Mock private ServiceRepository serviceRepository;
    @Mock private KubernetesDiscoveryService kubernetesDiscoveryService;
    @Mock private LeaderElectionService leaderElectionService;

    @InjectMocks private KubernetesPollingService poller;

    private void givenLeaderWithPods(List<Map<String, Object>> pods) {
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(serviceRegistry.getAllServices())
                .thenReturn(List.of(new Service("svc", "http://svc:8080", "http://svc:8080/docs")));
        when(kubernetesDiscoveryService.getPodsByLabel(any(), any())).thenReturn(pods);
    }

    @Test
    @DisplayName("a ready pod is reported through the registry, never straight to Redis")
    void writesThroughRegistry() {
        givenLeaderWithPods(List.of(Map.of("ready", true, "status", "Running")));

        poller.pollKubernetesStatus();

        verify(serviceRegistry).updateServiceStatus("svc", ServiceStatus.HEALTHY);
        verify(serviceRepository, never()).save(any(Service.class));
    }

    @Test
    @DisplayName("no pods means unavailable; a non-ready pod means not-ready")
    void statusMappingUsesThePlatformVocabulary() {
        givenLeaderWithPods(List.of());
        poller.pollKubernetesStatus();
        verify(serviceRegistry).updateServiceStatus("svc", ServiceStatus.UNAVAILABLE);

        reset(serviceRegistry);
        givenLeaderWithPods(List.of(Map.of("ready", false, "status", "Running")));
        poller.pollKubernetesStatus();
        verify(serviceRegistry).updateServiceStatus("svc", ServiceStatus.NOT_READY);
    }

    @Test
    @DisplayName("a non-leader replica polls nothing at all")
    void nonLeaderDoesNothing() {
        when(leaderElectionService.isLeader()).thenReturn(false);

        poller.pollKubernetesStatus();

        verify(serviceRegistry, never()).updateServiceStatus(any(), any());
        verify(serviceRegistry, never()).getAllServices();
    }
}
