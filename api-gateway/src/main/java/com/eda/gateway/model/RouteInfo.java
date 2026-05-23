package com.eda.gateway.model;

public class RouteInfo {

    private String serviceName;
    private String url;
    private String openapiUrl;
    private String status;

    public RouteInfo() {}

    public RouteInfo(String serviceName, String url, String openapiUrl, String status) {
        this.serviceName = serviceName;
        this.url = url;
        this.openapiUrl = openapiUrl;
        this.status = status;
    }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getOpenapiUrl() { return openapiUrl; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
