package com.example.testProj.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Health check configuration that reads from application.properties
 * 
 * Example:
 * health-check.interval-ms=15000
 * health-check.max-retries=2
 * health-check.failure-threshold=3
 * health-check.timeout-ms=5000
 */
@Component
@ConfigurationProperties(prefix = "health-check")
public class HealthCheckConfig {
    
    private long intervalMs = 15000;        // Check health every 15 seconds
    private int maxRetries = 2;             // Retry failed checks 2 times
    private int failureThreshold = 3;       // Mark unhealthy after 3 consecutive failures
    private long timeoutMs = 5000;          // 5 second timeout per HTTP request
    
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
