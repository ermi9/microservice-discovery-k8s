package com.example.testProj.service;

import com.example.testProj.kubernetes.KubernetesDiscoveryService;
import com.example.testProj.model.Service;
import com.example.testProj.repository.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class KubernetesPollingService {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private KubernetesDiscoveryService kubernetesDiscoveryService;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private LeaderElectionService leaderElectionService;

    @Scheduled(fixedRate = 15000)
    public void pollKubernetesStatus() {
        if (!leaderElectionService.isLeader()) return;
        List<Service> services = serviceRegistry.getAllServices();
        
        for (Service service : services) {
            try {
                // Query K8s for Pods with label 
                String labelSelector = "app=" + service.getName();
                List<Map<String, Object>> pods = kubernetesDiscoveryService.getPodsByLabel("default", labelSelector);
                
                // Find first running and ready pod
                Map<String, Object> readyPod = null;
                for (Map<String, Object> pod : pods) {
                    Boolean isReady = (Boolean) pod.get("ready");
                    String status = (String) pod.get("status");
                    
                    if (isReady != null && isReady && "Running".equals(status)) {
                        readyPod = pod;
                        break;
                    }
                }
                
                // Update service status based on pod readiness (pod data is fetched live by controller, not stored)
                if (readyPod != null) {
                    service.setStatus("healthy");
                } else if (pods.isEmpty()) {
                    service.setStatus("unavailable");
                } else {
                    service.setStatus("not-ready");
                }
                service.setUpdatedAt(java.time.LocalDateTime.now());
                serviceRepository.save(service);

            } catch (Exception e) {
                System.err.println("Error polling K8s for service " + service.getName() + ": " + e.getMessage());
            }
        }
    }
}
