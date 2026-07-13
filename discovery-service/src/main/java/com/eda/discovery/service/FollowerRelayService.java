package com.eda.discovery.service;

import com.eda.discovery.kubernetes.KubernetesDiscoveryService;
import com.eda.discovery.model.RelayEvent;
import com.eda.discovery.model.Service;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@org.springframework.stereotype.Service
public class FollowerRelayService {

    @Autowired
    private RedisMessageListenerContainer listenerContainer;

    @Autowired
    private PartitionLeaderElectionService partitionElection;

    @Autowired
    private PartitionManager partitionManager;

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private KubernetesDiscoveryService kubernetesDiscoveryService;

    // serviceName → last known status, kept current via relay messages
    private final Map<String, String> warmState = new ConcurrentHashMap<>();

    // partitionId → active MessageListener (null if not subscribed)
    private final Map<Integer, MessageListener> activeListeners = new ConcurrentHashMap<>();

    // partitionId → whether we are currently subscribed as follower
    private final Map<Integer, Boolean> subscribedPartitions = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void start() {
        executor.submit(this::monitorLoop);
    }

    // Polls partition leadership every 5s and adjusts subscriptions accordingly
    private void monitorLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (int p = 0; p < partitionManager.getPartitionCount(); p++) {
                    boolean amLeader    = partitionElection.isLeaderForPartition(p);
                    boolean subscribed  = subscribedPartitions.getOrDefault(p, false);

                    if (!amLeader && !subscribed) {
                        // Became follower — resync then subscribe
                        resyncPartition(p);
                        subscribeToPartition(p);
                    } else if (amLeader && subscribed) {
                        // Became leader — unsubscribe, leader gets state from Watch stream
                        unsubscribeFromPartition(p);
                    }
                }
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // One-time K8s List call to rebuild warm state for this partition before subscribing
    private void resyncPartition(int partitionId) {
        List<Service> services = serviceRegistry.getAllServices();
        for (Service service : services) {
            if (partitionManager.getPartitionForService(service.getName()) != partitionId) continue;
            try {
                String labelSelector = "app=" + service.getName();
                List<Map<String, Object>> pods =
                    kubernetesDiscoveryService.getPodsByLabel("default", labelSelector);
                String status = resolveStatus(pods);
                warmState.put(service.getName(), status);
                System.out.println("[Relay] Resynced " + service.getName() + " → " + status);
            } catch (Exception e) {
                System.err.println("[Relay] Resync failed for " + service.getName() + ": " + e.getMessage());
            }
        }
    }

    private void subscribeToPartition(int partitionId) {
        String channel = "partition:" + partitionId + ":relay";

        MessageListener listener = new MessageListener() {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                try {
                    String body = new String(message.getBody());
                    RelayEvent event = mapper.readValue(body, RelayEvent.class);
                    warmState.put(event.getServiceName(), event.getStatus());
                } catch (Exception e) {
                    System.err.println("[Relay] Failed to parse relay event: " + e.getMessage());
                }
            }
        };

        activeListeners.put(partitionId, listener);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        subscribedPartitions.put(partitionId, true);
        System.out.println("[Relay] Subscribed as follower for partition-" + partitionId);
    }

    private void unsubscribeFromPartition(int partitionId) {
        MessageListener listener = activeListeners.remove(partitionId);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener,
                new ChannelTopic("partition:" + partitionId + ":relay"));
        }
        subscribedPartitions.put(partitionId, false);
        System.out.println("[Relay] Unsubscribed from partition-" + partitionId + " (now leader)");
    }

    private String resolveStatus(List<Map<String, Object>> pods) {
        if (pods.isEmpty()) return "unavailable";
        for (Map<String, Object> pod : pods) {
            Boolean ready = (Boolean) pod.get("ready");
            String phase  = (String) pod.get("status");
            if (Boolean.TRUE.equals(ready) && "Running".equals(phase)) return "healthy";
        }
        return "not-ready";
    }

    public String getWarmStatus(String serviceName) {
        return warmState.get(serviceName);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
