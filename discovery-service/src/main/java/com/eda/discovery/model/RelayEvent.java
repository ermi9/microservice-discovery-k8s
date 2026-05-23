package com.eda.discovery.model;

public class RelayEvent {

    private String serviceName;
    private String status;
    private String resourceVersion;
    private long generation;

    public RelayEvent() {}

    public RelayEvent(String serviceName, String status, String resourceVersion, long generation) {
        this.serviceName = serviceName;
        this.status = status;
        this.resourceVersion = resourceVersion;
        this.generation = generation;
    }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResourceVersion() { return resourceVersion; }
    public void setResourceVersion(String resourceVersion) { this.resourceVersion = resourceVersion; }

    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }
}
