package com.example.testProj.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.UUID;

@RedisHash("pod_snapshots")
public class PodSnapshot {
    
    @Id
    private UUID id;
    
    @JsonIgnore
    @Indexed
    private UUID serviceId;
    
    private String podName;
    private String podIp;
    private String phase; // "Running", "Pending", "Failed"
    private Boolean ready;
    private LocalDateTime recordedAt;
    
    // Constructors
    public PodSnapshot() {
        this.id = UUID.randomUUID();
    }
    
    public PodSnapshot(UUID serviceId, String podName, String podIp, String phase, Boolean ready) {
        this.id = UUID.randomUUID();
        this.serviceId = serviceId;
        this.podName = podName;
        this.podIp = podIp;
        this.phase = phase;
        this.ready = ready;
        this.recordedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public UUID getId() {
        return id;
    }
    
    public void setId(UUID id) {
        this.id = id;
    }
    
    public UUID getServiceId() {
        return serviceId;
    }
    
    public void setServiceId(UUID serviceId) {
        this.serviceId = serviceId;
    }
    
    public String getPodName() {
        return podName;
    }
    
    public void setPodName(String podName) {
        this.podName = podName;
    }
    
    public String getPodIp() {
        return podIp;
    }
    
    public void setPodIp(String podIp) {
        this.podIp = podIp;
    }
    
    public String getPhase() {
        return phase;
    }
    
    public void setPhase(String phase) {
        this.phase = phase;
    }
    
    public Boolean getReady() {
        return ready;
    }
    
    public void setReady(Boolean ready) {
        this.ready = ready;
    }
    
    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }
    
    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
