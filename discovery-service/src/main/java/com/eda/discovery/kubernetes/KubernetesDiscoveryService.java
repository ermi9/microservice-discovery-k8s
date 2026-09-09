package com.eda.discovery.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.eda.discovery.service.CacheService;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@Service
public class KubernetesDiscoveryService {

    private OkHttpClient httpClient;
    private String basePath;
    private String token;
    private ObjectMapper mapper;
    
    @Autowired
    private CacheService cacheService;
    
    private static final long CACHE_TTL_SECONDS = 30; // Cache K8s data for 30 seconds
    private static final long EMPTY_CACHE_TTL_SECONDS = 5; // Negative results expire sooner

    /** True when the client initialised; false outside a cluster or without a token. */
    private boolean available;

    public KubernetesDiscoveryService() {
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            ApiClient client = ClientBuilder.standard().build();
            this.httpClient = client.getHttpClient();
            this.basePath = client.getBasePath();

            // Read service account token from pod
            String tokenPath = "/var/run/secrets/kubernetes.io/serviceaccount/token";
            this.token = new String(Files.readAllBytes(Paths.get(tokenPath)));
            this.available = true;
        } catch (Exception e) {
            // Record the failure rather than leaving httpClient null: queries then
            // degrade to an empty result with one clear message, instead of an NPE
            // raised deep inside queryK8sForPods on every later call.
            this.available = false;
            System.err.println("Failed to initialize K8s client — pod lookups will return empty: "
                    + e.getMessage());
        }
    }

    /** Whether Kubernetes lookups can be served at all. */
    public boolean isAvailable() {
        return available;
    }

    /** All pods in a namespace, cached for {@link #CACHE_TTL_SECONDS}. */
    public List<Map<String, Object>> getAllPods(String namespace) {
        String cacheKey = "k8s:pods:" + namespace + ":*";

        Object cached = cacheService.get(cacheKey);
        if (cached != null) {
            return (List<Map<String, Object>>) cached;
        }

        List<Map<String, Object>> pods = queryK8sForPods(namespace, null);
        cacheService.set(cacheKey, pods, CACHE_TTL_SECONDS);
        return pods;
    }

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

        // Empty results are cached too, on a shorter TTL. Caching only non-empty results
        // meant a service with no pods was re-queried against the Kubernetes API on every
        // single call — the heaviest query load landed exactly when the cluster was
        // already degraded. The shorter TTL keeps recovery detection quick.
        long ttl = pods.isEmpty() ? EMPTY_CACHE_TTL_SECONDS : CACHE_TTL_SECONDS;
        cacheService.set(cacheKey, pods, ttl);
        System.out.println("Cached " + pods.size() + " pods for " + labelSelector + " (TTL: " + ttl + "s)");

        return pods;
    }
    
    private List<Map<String, Object>> queryK8sForPods(String namespace, String labelSelector) {
        List<Map<String, Object>> pods = new ArrayList<>();

        if (!available) {
            return pods;
        }

        try {
            String url = basePath + "/api/v1/namespaces/" + namespace + "/pods";
            if (labelSelector != null) {
                url += "?labelSelector=" + URLEncoder.encode(labelSelector, StandardCharsets.UTF_8);
            }

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + token)
                    .build();

            // try-with-resources: the response was never closed, so every cache-miss
            // poll leaked a connection and its body — and the leak got worse the more
            // degraded the cluster was, because misses became more frequent.
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    System.err.println("K8s API returned " + response.code() + " for " + url);
                    return pods;
                }

                Map<String, Object> result = mapper.readValue(responseBody.string(), Map.class);
                List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

                if (items != null) {
                    for (Map<String, Object> item : items) {
                        Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                        Map<String, Object> status = (Map<String, Object>) item.get("status");
                        if (metadata == null || status == null) continue;

                        Map<String, Object> podInfo = new HashMap<>();
                        podInfo.put("name", metadata.get("name"));
                        podInfo.put("status", status.get("phase"));
                        podInfo.put("ip", status.get("podIP"));
                        podInfo.put("labels", metadata.get("labels"));
                        podInfo.put("ready", isPodReady(status));

                        pods.add(podInfo);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error querying K8s API: " + e.getMessage());
        }

        return pods;
    }

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
