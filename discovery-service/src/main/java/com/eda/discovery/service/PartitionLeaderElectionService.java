package com.eda.discovery.service;

import com.eda.discovery.config.LeaderElectionConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PartitionLeaderElectionService {

    private static final String LEADER_KEY    = "discovery:partition:%d:leader";
    private static final String GEN_KEY       = "discovery:partition:%d:generation";

    // Same Lua renewal script as LeaderElectionService — atomically extends TTL only if still owner
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('expire', KEYS[1], ARGV[2]) " +
        "else " +
        "  return 0 " +
        "end",
        Long.class
    );

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LeaderElectionConfig config;

    @Autowired
    private PartitionManager partitionManager;

    private final String replicaId = resolveReplicaId();

    // Per-partition leadership flag and cached generation
    private final Map<Integer, AtomicBoolean> leadershipMap  = new ConcurrentHashMap<>();
    private final Map<Integer, Long>          generationCache = new ConcurrentHashMap<>();

    private static String resolveReplicaId() {
        String podName = System.getenv("POD_NAME");
        return (podName != null && !podName.isBlank()) ? podName : UUID.randomUUID().toString();
    }

    @PostConstruct
    public void init() {
        for (int i = 0; i < partitionManager.getPartitionCount(); i++) {
            leadershipMap.put(i, new AtomicBoolean(false));
            generationCache.put(i, 0L);
        }
    }

    @Scheduled(fixedDelayString = "#{@leaderElectionConfig.getRenewalIntervalMs()}")
    public void tryAcquireOrRenewAll() {
        for (int partitionId = 0; partitionId < partitionManager.getPartitionCount(); partitionId++) {
            tryAcquireOrRenew(partitionId);
        }
    }

    private void tryAcquireOrRenew(int partitionId) {
        AtomicBoolean isLeader = leadershipMap.get(partitionId);
        String leaderKey = String.format(LEADER_KEY, partitionId);

        if (isLeader.get()) {
            Long renewed = redisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(leaderKey),
                replicaId,
                String.valueOf(config.getTtlSeconds())
            );
            if (Long.valueOf(1L).equals(renewed)) return;
            isLeader.set(false);
            System.out.println("[PartitionElection] Lost partition-" + partitionId + ": " + replicaId);
        }

        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(leaderKey, replicaId, Duration.ofSeconds(config.getTtlSeconds()));

        if (Boolean.TRUE.equals(acquired)) {
            isLeader.set(true);
            long gen = incrementGeneration(partitionId);
            generationCache.put(partitionId, gen);
            System.out.println("[PartitionElection] Won partition-" + partitionId
                + " gen=" + gen + ": " + replicaId);
        }
    }

    private long incrementGeneration(int partitionId) {
        Long gen = redisTemplate.opsForValue().increment(String.format(GEN_KEY, partitionId));
        return gen != null ? gen : 1L;
    }

    public boolean isLeaderForPartition(int partitionId) {
        AtomicBoolean b = leadershipMap.get(partitionId);
        return b != null && b.get();
    }

    public long getGeneration(int partitionId) {
        return generationCache.getOrDefault(partitionId, 0L);
    }

    public String getReplicaId() {
        return replicaId;
    }

    @PreDestroy
    public void relinquishAll() {
        for (int partitionId = 0; partitionId < partitionManager.getPartitionCount(); partitionId++) {
            AtomicBoolean isLeader = leadershipMap.get(partitionId);
            if (isLeader == null || !isLeader.get()) continue;
            redisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(String.format(LEADER_KEY, partitionId)),
                replicaId,
                "0"
            );
            isLeader.set(false);
            System.out.println("[PartitionElection] Relinquished partition-" + partitionId + ": " + replicaId);
        }
    }
}
