package com.eda.discovery.model;

import java.time.Instant;

public class ServiceEvent {

    public enum Type {
        SERVICE_REGISTERED,
        SERVICE_DEREGISTERED,
        STATUS_CHANGED
    }

    private Type type;
    private String serviceName;
    private String url;
    private String openapiUrl;
    private String status;
    private String timestamp;
    private long generation;

    public ServiceEvent() {}

    public ServiceEvent(Type type, String serviceName, String url, String status) {
        this.type = type;
        this.serviceName = serviceName;
        this.url = url;
        this.status = status;
        this.timestamp = Instant.now().toString();
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getOpenapiUrl() { return openapiUrl; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }
}
