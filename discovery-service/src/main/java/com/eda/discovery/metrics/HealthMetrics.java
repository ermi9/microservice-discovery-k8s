package com.eda.discovery.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class HealthMetrics {

    private final String serviceName;
    private final AtomicLong totalChecks = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private volatile long lastResponseMs = 0;

    public HealthMetrics(String serviceName) {
        this.serviceName = serviceName;
    }

    public void recordSuccess(long durationMs) {
        totalChecks.incrementAndGet();
        successCount.incrementAndGet();
        lastResponseMs = durationMs;
    }

    public void recordFailure() {
        totalChecks.incrementAndGet();
        failureCount.incrementAndGet();
    }

    // Served over HTTP by DiscoveryController at /services/{name}/metrics.

    public String getServiceName() {
        return serviceName;
    }

    public long getTotalChecks() {
        return totalChecks.get();
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    public long getLastResponseMs() {
        return lastResponseMs;
    }

    /** Success ratio over all recorded checks; 0 when nothing has been checked yet. */
    public double getSuccessRate() {
        long total = totalChecks.get();
        return total == 0 ? 0.0 : (double) successCount.get() / total;
    }

    @Override
    public String toString() {
        long total = totalChecks.get();
        long success = successCount.get();
        return serviceName + " [checks=" + total + ", ok=" + success
                + ", fail=" + failureCount.get()
                + ", lastMs=" + lastResponseMs + "]";
    }
}
