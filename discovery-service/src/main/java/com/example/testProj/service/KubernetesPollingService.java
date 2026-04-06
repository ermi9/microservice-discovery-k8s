package com.example.testProj.service;

import com.example.testProj.kubernetes.KubernetesDiscoveryService;
import com.example.testProj.model.Service;
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
    
    /**
     * Poll K8s API every 15 seconds to get latest Pod readiness status
     */
    @Scheduled(fixedRate = 15000)
    public void pollKubernetesStatus() {
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
                
                // 
                if (readyPod != null) {
                    service.setStatus("healthy");
                } else if (pods.isEmpty()) {
                    service.setStatus("unavailable");
                } else {
                    service.setStatus("not-ready");
                }
                
            } catch (Exception e) {
                service.setStatus("error");
                System.err.println("Error polling K8s for service " + service.getName() + ": " + e.getMessage());
            }
        }
    }
}