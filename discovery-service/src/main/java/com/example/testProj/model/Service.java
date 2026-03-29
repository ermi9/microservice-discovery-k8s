package com.example.testProj.model;
import java.time.LocalDateTime;


//data model for service registry
public class Service {
    private String name;
    private String url;
    private String openapiUrl;
    private String status;
    private LocalDateTime lastHeartbeat;

    public Service() {
        // Default constructor for JSON deserialization
    }
    public Service(String name, String url, String openapiUrl) {
        this.name = name;
        this.url = url;
        this.openapiUrl = openapiUrl;
        this.status = "unknown";
        this.lastHeartbeat = LocalDateTime.now();
    }


    public String getName() { return this.name; }
    public String getUrl() { return this.url; }
    public String getOpenapiUrl() { return this.openapiUrl; }
    public String getStatus() { return  this.status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastHeartbeat() { return this.lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime time) { this.lastHeartbeat = time; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }
    public void setUrl(String url) { this.url = url; }
    public void setName(String name) { this.name = name; }
    
}
