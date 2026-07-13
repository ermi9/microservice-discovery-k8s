package com.eda.discovery.kubernetes;

import com.eda.discovery.model.RelayEvent;
import com.eda.discovery.model.Service;
import com.eda.discovery.service.PartitionLeaderElectionService;
import com.eda.discovery.service.PartitionManager;
import com.eda.discovery.service.ServiceRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.util.ClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@org.springframework.stereotype.Service
public class KubernetesWatchService {

    // Redis key templates
    private static final String RV_KEY     = "partition:%d:rv:%s";   // last resourceVersion per service
    private static final String RELAY_CHAN  = "partition:%d:relay";   // pub/sub channel per partition

    @Autowired
    private PartitionLeaderElectionService partitionElection;

    @Autowired
    private PartitionManager partitionManager;

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private OkHttpClient httpClient;
    private String basePath;
    private String token;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ExecutorService watchExecutor = Executors.newCachedThreadPool();

    // serviceName → running watch Future
    private final Map<String, Future<?>> activeWatches = new ConcurrentHashMap<>();

    // partitionId → whether we were leading last check
    private final Map<Integer, Boolean> wasLeaderMap = new HashMap<>();

    private boolean initialized = false;

    @PostConstruct
    public void init() {
        try {
            var apiClient = ClientBuilder.standard().build();
            this.httpClient = apiClient.getHttpClient().newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            this.basePath = apiClient.getBasePath();
            this.token = new String(Files.readAllBytes(
                    Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/token")));
            this.initialized = true;
        } catch (Exception e) {
            System.err.println("[Watch] K8s client init failed — watch disabled: " + e.getMessage());
            return;
        }

        for (int i = 0; i < partitionManager.getPartitionCount(); i++) {
            wasLeaderMap.put(i, false);
        }

        watchExecutor.submit(this::monitorLeadership);
    }

    // Polls per-partition leadership every 5s and starts/stops watches accordingly
    private void monitorLeadership() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (int partitionId = 0; partitionId < partitionManager.getPartitionCount(); partitionId++) {
                    boolean nowLeader  = partitionElection.isLeaderForPartition(partitionId);
                    boolean wasLeader  = wasLeaderMap.getOrDefault(partitionId, false);

                    if (nowLeader && !wasLeader) {
                        System.out.println("[Watch] Won partition-" + partitionId + " — starting watches");
                        startWatchesForPartition(partitionId);
                        wasLeaderMap.put(partitionId, true);
                    } else if (!nowLeader && wasLeader) {
                        System.out.println("[Watch] Lost partition-" + partitionId + " — stopping watches");
                        stopWatchesForPartition(partitionId);
                        wasLeaderMap.put(partitionId, false);
                    }
                }
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void startWatchesForPartition(int partitionId) {
        List<Service> services = serviceRegistry.getAllServices();
        for (Service service : services) {
            if (partitionManager.getPartitionForService(service.getName()) == partitionId) {
                startWatchForService(service.getName(), partitionId);
            }
        }
    }

    private void stopWatchesForPartition(int partitionId) {
        List<Service> services = serviceRegistry.getAllServices();
        for (Service service : services) {
            if (partitionManager.getPartitionForService(service.getName()) == partitionId) {
                Future<?> future = activeWatches.remove(service.getName());
                if (future != null) future.cancel(true);
            }
        }
    }

    public void startWatchForService(String serviceName, int partitionId) {
        if (activeWatches.containsKey(serviceName)) return;
        Future<?> future = watchExecutor.submit(() -> watchLoop(serviceName, partitionId));
        activeWatches.put(serviceName, future);
        System.out.println("[Watch] Watch started for: " + serviceName + " (partition-" + partitionId + ")");
    }

    // Streaming loop per service. Reads last-rv from Redis to resume, handles 410 Gone.
    private void watchLoop(String serviceName, int partitionId) {
        String labelSelector = "app=" + serviceName;
        String rvKey         = String.format(RV_KEY, partitionId, serviceName);
        String relayChannel  = String.format(RELAY_CHAN, partitionId);

        while (!Thread.currentThread().isInterrupted()
                && partitionElection.isLeaderForPartition(partitionId)) {
            try {
                Object stored = redisTemplate.opsForValue().get(rvKey);
                String startRv = (stored instanceof String s) ? s : "0";

                String encoded = URLEncoder.encode(labelSelector, StandardCharsets.UTF_8);
                String url = basePath + "/api/v1/namespaces/default/pods?labelSelector="
                        + encoded + "&watch=true&resourceVersion=" + startRv;

                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + token)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.code() == 410) {
                        // Stored resourceVersion is too old — clear it and retry from scratch
                        System.out.println("[Watch] 410 Gone for " + serviceName + " — resetting rv");
                        redisTemplate.delete(rvKey);
                        continue;
                    }

                    if (!response.isSuccessful() || response.body() == null) {
                        System.err.println("[Watch] Bad response for " + serviceName
                                + ": HTTP " + response.code());
                        Thread.sleep(5_000);
                        continue;
                    }

                    System.out.println("[Watch] Stream open for: " + serviceName
                            + " (partition-" + partitionId + ", rv=" + startRv + ")");

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body().byteStream()));

                    String line;
                    while ((line = reader.readLine()) != null
                            && !Thread.currentThread().isInterrupted()
                            && partitionElection.isLeaderForPartition(partitionId)) {
                        if (!line.isBlank()) {
                            String newRv = processEvent(serviceName, line, partitionId);
                            if (newRv != null) {
                                redisTemplate.opsForValue().set(rvKey, newRv);
                                publishRelay(serviceName, partitionId, newRv, relayChannel);
                            }
                        }
                    }
                }

                System.out.println("[Watch] Stream closed for " + serviceName + " — reconnecting");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[Watch] Error in watch loop for " + serviceName
                        + ": " + e.getMessage());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        activeWatches.remove(serviceName);
        System.out.println("[Watch] Watch loop ended for: " + serviceName);
    }

    // Returns the resourceVersion extracted from the event, or null if event was skipped.
    @SuppressWarnings("unchecked")
    private String processEvent(String serviceName, String eventJson, int partitionId) {
        try {
            Map<String, Object> event  = mapper.readValue(eventJson, Map.class);
            String eventType           = (String) event.get("type");
            Map<String, Object> object = (Map<String, Object>) event.get("object");

            if (object == null || "BOOKMARK".equals(eventType)) return null;

            Map<String, Object> metadata   = (Map<String, Object>) object.get("metadata");
            String resourceVersion = (metadata != null) ? (String) metadata.get("resourceVersion") : null;

            if ("ERROR".equals(eventType)) {
                Integer code = (Integer) object.get("code");
                System.err.println("[Watch] ERROR event for " + serviceName
                        + ", code=" + code + " — reconnecting");
                throw new RuntimeException("Watch ERROR event code=" + code);
            }

            Service service = serviceRegistry.getServiceByName(serviceName);
            if (service == null) return resourceVersion;

            Map<String, Object> statusObj = (Map<String, Object>) object.get("status");
            String newStatus = null;

            switch (eventType) {
                case "ADDED", "MODIFIED" -> {
                    if (statusObj != null) {
                        String phase = (String) statusObj.get("phase");
                        boolean ready = isPodReady(statusObj);
                        if ("Running".equals(phase) && ready) {
                            newStatus = "healthy";
                        } else if ("Running".equals(phase)) {
                            newStatus = "not-ready";
                        } else {
                            newStatus = "unavailable";
                        }
                    }
                }
                case "DELETED" -> newStatus = "unavailable";
            }

            if (newStatus != null) {
                long generation = partitionElection.getGeneration(partitionId);
                serviceRegistry.updateServiceStatus(serviceName, newStatus, generation);
                System.out.println("[Watch] " + eventType + " → " + serviceName
                        + " = " + newStatus + " (gen=" + generation + ")");
            }

            return resourceVersion;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("[Watch] Failed to parse event for " + serviceName
                    + ": " + e.getMessage());
            return null;
        }
    }

    // Broadcasts the processed event to followers via Redis pub/sub
    private void publishRelay(String serviceName, int partitionId, String resourceVersion, String channel) {
        try {
            Service service = serviceRegistry.getServiceByName(serviceName);
            if (service == null) return;

            RelayEvent relayEvent = new RelayEvent(
                    serviceName,
                    service.getStatus(),
                    resourceVersion,
                    partitionElection.getGeneration(partitionId)
            );
            String json = mapper.writeValueAsString(relayEvent);
            stringRedisTemplate.convertAndSend(channel, json);
        } catch (Exception e) {
            System.err.println("[Watch] Failed to publish relay for " + serviceName
                    + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isPodReady(Map<String, Object> status) {
        try {
            List<Map<String, Object>> conditions =
                    (List<Map<String, Object>>) status.get("conditions");
            if (conditions == null) return false;
            for (Map<String, Object> cond : conditions) {
                if ("Ready".equals(cond.get("type")) && "True".equals(cond.get("status"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Future<?> future : activeWatches.values()) {
            future.cancel(true);
        }
        watchExecutor.shutdownNow();
    }
}
