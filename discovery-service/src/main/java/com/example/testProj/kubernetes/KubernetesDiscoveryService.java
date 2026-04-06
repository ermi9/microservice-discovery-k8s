package com.example.testProj.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.util.ClientBuilder;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KubernetesDiscoveryService {

    private CoreV1Api api;

    public KubernetesDiscoveryService() {
        try {
            ApiClient client = ClientBuilder.standard().build();
            this.api = new CoreV1Api(client);
        } catch (Exception e) {
            System.err.println("Failed to initialize K8s client: " + e.getMessage());
        }
    }

    /**
     * Get all Pods from the Kubernetes cluster
     */
    public List<Map<String, Object>> getAllPods(String namespace) {
        List<Map<String, Object>> pods = new ArrayList<>();
        
        try {
            V1PodList podList = api.listNamespacedPod(namespace).execute();

            for (V1Pod pod : podList.getItems()) {
                Map<String, Object> podInfo = new HashMap<>();
                podInfo.put("name", pod.getMetadata().getName());
                podInfo.put("status", pod.getStatus().getPhase());
                podInfo.put("ip", pod.getStatus().getPodIP());
                podInfo.put("labels", pod.getMetadata().getLabels());
                
                pods.add(podInfo);
            }
        } catch (ApiException e) {
            System.err.println("Failed to get pods from K8s: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error getting pods: " + e.getMessage());
        }

        return pods;
    }

    /**
     * Get Pods filtered by label selector
     */
    public List<Map<String, Object>> getPodsByLabel(String namespace, String labelSelector) {
        List<Map<String, Object>> pods = new ArrayList<>();
        
        try {
            V1PodList podList = api.listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .execute();

            for (V1Pod pod : podList.getItems()) {
                Map<String, Object> podInfo = new HashMap<>();
                podInfo.put("name", pod.getMetadata().getName());
                podInfo.put("status", pod.getStatus().getPhase());
                podInfo.put("ip", pod.getStatus().getPodIP());
                podInfo.put("labels", pod.getMetadata().getLabels());
                podInfo.put("ready", isPodReady(pod));
                
                pods.add(podInfo);
            }
        } catch (ApiException e) {
            System.err.println("Failed to get pods by label: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error getting pods by label: " + e.getMessage());
        }

        return pods;
    }
    private boolean isPodReady(V1Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        for (V1PodCondition cond: pod.getStatus().getConditions()) {
            if (cond.getType().equals("Ready")){
                if(cond.getStatus().equals("True")){
                    return true;
                }
            }

            }
            return false;
        }

    }
