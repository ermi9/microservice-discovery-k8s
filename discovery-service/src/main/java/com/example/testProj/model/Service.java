package com.example.testProj.model;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Service model - represents a registered microservice
 */
public class Service {
    private String name;
    private String url;
    private String openapiUrl;
    private String status;
    private Map<String, Object> pod;
    
    // NEW FIELDS for health checking
    @JsonIgnore
    private String healthEndpoint = "/health";        // Configurable endpoint to check
    
    @JsonIgnore
    private int consecutiveFailures = 0;              // Track failures in a row
    
    public Service() {
        // Default constructor for JSON deserialization
    }
    
    public Service(String name, String url, String openapiUrl) {
        this.name = name;
        this.url = url;
        this.openapiUrl = openapiUrl;
        this.status = "unknown";
        this.pod = null;
        this.healthEndpoint = "/health";
        this.consecutiveFailures = 0;
    }
    
    // Original getters and setters
    public String getName() { return this.name; }
    public String getUrl() { return this.url; }
    public String getOpenapiUrl() { return this.openapiUrl; }
    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }
    public void setUrl(String url) { this.url = url; }
    public void setName(String name) { this.name = name; }
    public Map<String, Object> getPod() { return this.pod; }
    public void setPod(Map<String, Object> pod) { this.pod = pod; }
    
    // NEW: Health endpoint getter/setter
    public String getHealthEndpoint() { return this.healthEndpoint; }
    public void setHealthEndpoint(String endpoint) { this.healthEndpoint = endpoint; }
    
    // NEW: Failure counter methods
    public int getConsecutiveFailures() { return this.consecutiveFailures; }
    public void incrementFailure() { this.consecutiveFailures++; }
    public void resetFailures() { this.consecutiveFailures = 0; }
}
