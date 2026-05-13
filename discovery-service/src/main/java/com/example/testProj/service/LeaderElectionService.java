package com.example.testProj.service;

import com.example.testProj.config.LeaderElectionConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class LeaderElectionService {

    private static final String LEADER_KEY = "discovery:leader";

    // Atomically extend TTL only if this replica still owns the key
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

    private final String replicaId = resolveReplicaId();
    private final AtomicBoolean leader = new AtomicBoolean(false);

    private static String resolveReplicaId() {
        String podName = System.getenv("POD_NAME");
        return (podName != null && !podName.isBlank()) ? podName : UUID.randomUUID().toString();
    }

    @Scheduled(fixedDelayString = "#{@leaderElectionConfig.getRenewalIntervalMs()}")
    public void tryAcquireOrRenew() {
        if (leader.get()) {
            Long renewed = redisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(LEADER_KEY),
                replicaId,
                String.valueOf(config.getTtlSeconds())
            );
            if (Long.valueOf(1L).equals(renewed)) {
                return; // still leader, TTL refreshed
            }
            // Lost leadership (e.g. Redis failover or TTL expired before renewal)
            leader.set(false);
            System.out.println("[LeaderElection] Lost leadership: " + replicaId);
        }

        // Attempt acquisition
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LEADER_KEY, replicaId, Duration.ofSeconds(config.getTtlSeconds()));

        if (Boolean.TRUE.equals(acquired)) {
            leader.set(true);
            System.out.println("[LeaderElection] Acquired leadership: " + replicaId);
        }
    }

    public boolean isLeader() {
        return leader.get();
    }

    public String getReplicaId() {
        return replicaId;
    }

    @PreDestroy
    public void relinquish() {
        if (!leader.get()) return;
        // Atomically delete only if we still own the key so we don't evict a successor
        redisTemplate.execute(
            RENEW_SCRIPT,
            Collections.singletonList(LEADER_KEY),
            replicaId,
            "0"  // expire(key, 0) immediately deletes it
        );
        leader.set(false);
        System.out.println("[LeaderElection] Relinquished leadership: " + replicaId);
    }
}
