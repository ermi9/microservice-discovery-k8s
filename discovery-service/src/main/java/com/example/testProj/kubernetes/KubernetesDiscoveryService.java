package com.example.testProj.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
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

    //get pods by label selector, this will be used to find pods associated with a service based on a label like "app=serviceName"
    public List<Map<String, Object>> getPodsByLabel(String namespace, String labelSelector) {
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
            System.err.println("Error getting pods by label: " + e.getMessage());
            e.printStackTrace();
        }

        return pods;
    }

    //readiness probe check for pods, this checks the conditions of the pod status to determine if it's ready
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
