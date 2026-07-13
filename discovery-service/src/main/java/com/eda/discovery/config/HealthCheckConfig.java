package com.eda.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "health-check")
public class HealthCheckConfig {

    private long intervalMs = 15000;
    private int maxRetries = 2;
    private int failureThreshold = 3;
    private long timeoutMs = 5000;
    
    public long getIntervalMs() {
        return intervalMs;
    }
    
    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public int getFailureThreshold() {
        return failureThreshold;
    }
    
    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
    
    @Override
    public String toString() {
        return "HealthCheckConfig{" +
                "intervalMs=" + intervalMs +
                ", maxRetries=" + maxRetries +
                ", failureThreshold=" + failureThreshold +
                ", timeoutMs=" + timeoutMs +
                '}';
    }
}
