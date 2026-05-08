package com.example.testProj.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.redis.core.RedisHash;

import java.time.LocalDateTime;
import java.util.Map;

@RedisHash("services")
public class Service {

    @Id
    private String name;

    private String url;
    private String openapiUrl;
    private String status; // "healthy", "degraded", "unhealthy", "unknown"
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int consecutiveFailures = 0;
    private String healthEndpoint = "/health";
    @Transient
    private Map<String, Object> pod;

    public Service() {}

    public Service(String name, String url, String openapiUrl) {
        this.name = name;
        this.url = url;
        this.openapiUrl = openapiUrl;
        this.status = "unknown";
        this.consecutiveFailures = 0;
        this.healthEndpoint = "/health";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getOpenapiUrl() {
        return openapiUrl;
    }

    public void setOpenapiUrl(String openapiUrl) {
        this.openapiUrl = openapiUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public void incrementFailure() {
        this.consecutiveFailures++;
        this.updatedAt = LocalDateTime.now();
    }

    public void resetFailures() {
        this.consecutiveFailures = 0;
        this.updatedAt = LocalDateTime.now();
    }

    public String getHealthEndpoint() {
        return healthEndpoint;
    }

    public void setHealthEndpoint(String healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    public Map<String, Object> getPod() {
        return this.pod;
    }

    public void setPod(Map<String, Object> pod) {
        this.pod = pod;
    }
}
