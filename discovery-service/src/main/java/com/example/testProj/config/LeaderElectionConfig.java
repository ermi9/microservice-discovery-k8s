package com.example.testProj.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "leader-election")
public class LeaderElectionConfig {

    private long ttlSeconds = 30;
    private long renewalIntervalMs = 10000;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getRenewalIntervalMs() {
        return renewalIntervalMs;
    }

    public void setRenewalIntervalMs(long renewalIntervalMs) {
        this.renewalIntervalMs = renewalIntervalMs;
    }
}
