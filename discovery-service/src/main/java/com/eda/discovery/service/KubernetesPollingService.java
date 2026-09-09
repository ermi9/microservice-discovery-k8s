package com.eda.discovery.service;

import com.eda.discovery.kubernetes.KubernetesDiscoveryService;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * One-shot reconciliation against the Kubernetes API.
 *
 * <p>Not scheduled: {@code KubernetesWatchService} replaced the polling loop and is the
 * authoritative status source. This remains as a manual fallback for the case where the
 * watch stream is wedged and a single forced resync is wanted.
 *
 * <p>Status is written through {@link ServiceRegistry#updateServiceStatus} like every
 * other writer, never straight to the repository: the registry is what applies the
 * generation fence and publishes to {@code service-events}, so writing around it would
 * change stored state without any consumer hearing about it.
 */
@Component
public class KubernetesPollingService {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private KubernetesDiscoveryService kubernetesDiscoveryService;

    @Autowired
    private LeaderElectionService leaderElectionService;

    public void pollKubernetesStatus() {
        if (!leaderElectionService.isLeader()) return;

        for (Service service : serviceRegistry.getAllServices()) {
            try {
                List<Map<String, Object>> pods = kubernetesDiscoveryService
                        .getPodsByLabel("default", "app=" + service.getName());

                Map<String, Object> readyPod = null;
                for (Map<String, Object> pod : pods) {
                    Boolean isReady = (Boolean) pod.get("ready");
                    String phase = (String) pod.get("status");
                    if (Boolean.TRUE.equals(isReady) && "Running".equals(phase)) {
                        readyPod = pod;
                        break;
                    }
                }

                String status;
                if (readyPod != null) {
                    status = ServiceStatus.HEALTHY;
                } else if (pods.isEmpty()) {
                    status = ServiceStatus.UNAVAILABLE;
                } else {
                    status = ServiceStatus.NOT_READY;
                }

                serviceRegistry.updateServiceStatus(service.getName(), status);

            } catch (Exception e) {
                System.err.println("Error polling K8s for service " + service.getName() + ": " + e.getMessage());
            }
        }
    }
}
