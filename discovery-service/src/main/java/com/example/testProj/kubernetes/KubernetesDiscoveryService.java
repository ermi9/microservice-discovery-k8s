package com.example.testProj.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.example.testProj.service.CacheService;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KubernetesDiscoveryService {

    private OkHttpClient httpClient;
    private String basePath;
    private String token;
    private ObjectMapper mapper;
    
    @Autowired
    private CacheService cacheService;
    
    private static final long CACHE_TTL_SECONDS = 30; // Cache K8s data for 30 seconds

    public KubernetesDiscoveryService() {
        try {
            ApiClient client = ClientBuilder.standard().build();
            this.httpClient = client.getHttpClient();
            this.basePath = client.getBasePath();
            
            // Read service account token from pod
            String tokenPath = "/var/run/secrets/kubernetes.io/serviceaccount/token";
            this.token = new String(Files.readAllBytes(Paths.get(tokenPath)));
            
            this.mapper = new ObjectMapper();
            this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        } catch (Exception e) {
            System.err.println("Failed to initialize K8s client: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> getAllPods(String namespace) {
        return new ArrayList<>();
    }

    /**
     * Get pods by label selector, with caching
     * First checks Redis cache (TTL 30s)
     * If cache miss, queries K8s API and stores in Redis
     */
    public List<Map<String, Object>> getPodsByLabel(String namespace, String labelSelector) {
        String cacheKey = "k8s:pods:" + namespace + ":" + labelSelector;
        
        // Check cache first
        Object cachedPods = cacheService.get(cacheKey);
        if (cachedPods != null) {
            System.out.println("Cache HIT for key: " + cacheKey);
            return (List<Map<String, Object>>) cachedPods;
        }
        
        System.out.println("Cache MISS for key: " + cacheKey + " - querying K8s API");
        List<Map<String, Object>> pods = queryK8sForPods(namespace, labelSelector);
        
        // Store in cache with 30-second TTL
        if (!pods.isEmpty()) {
            cacheService.set(cacheKey, pods, CACHE_TTL_SECONDS);
            System.out.println("Cached pods for " + labelSelector + " (TTL: " + CACHE_TTL_SECONDS + "s)");
        }
        
        return pods;
    }
    
    /**
     * Internal method: Query K8s API directly
     */
    private List<Map<String, Object>> queryK8sForPods(String namespace, String labelSelector) {
        List<Map<String, Object>> pods = new ArrayList<>();
        
        try {
            String url = basePath + "/api/v1/namespaces/" + namespace + "/pods?labelSelector=" + labelSelector;
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + token)
                    .build();
            
            Response response = httpClient.newCall(request).execute();
            String body = response.body().string();
            
            Map<String, Object> result = mapper.readValue(body, Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
            
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                    Map<String, Object> status = (Map<String, Object>) item.get("status");
                    
                    Map<String, Object> podInfo = new HashMap<>();
                    podInfo.put("name", metadata.get("name"));
                    podInfo.put("status", status.get("phase"));
                    podInfo.put("ip", status.get("podIP"));
                    podInfo.put("labels", metadata.get("labels"));
                    podInfo.put("ready", isPodReady(status));
                    
                    pods.add(podInfo);
                }
            }
        } catch (Exception e) {
            System.err.println("Error querying K8s API: " + e.getMessage());
            e.printStackTrace();
        }

        return pods;
    }

    /**
     * Check readiness probe from pod status
     */
    private boolean isPodReady(Map<String, Object> status) {
        try {
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> cond : conditions) {
                    if ("Ready".equals(cond.get("type")) && "True".equals(cond.get("status"))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
