package com.example.testProj.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "pod_snapshots")
public class PodSnapshot {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;
    
    @Column(nullable = false)
    private String podName;
    
    @Column(nullable = false)
    private String podIp;
    
    @Column(nullable = false)
    private String phase; // "Running", "Pending", "Failed"
    
    @Column(nullable = false)
    private Boolean ready;
    
    @Column(nullable = false)
    private LocalDateTime recordedAt;
    
    // Constructors
    public PodSnapshot() {
    }
    
    public PodSnapshot(Service service, String podName, String podIp, String phase, Boolean ready) {
        this.service = service;
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
    
    public Service getService() {
        return service;
    }
    
    public void setService(Service service) {
        this.service = service;
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
