package com.eda.discovery.model;

import java.time.Instant;

/**
 * The change-stream event published to {@code service-events}.
 *
 * <p>This is the platform's outward contract. Consumers (the API gateway, and the
 * choreography layer's resolution table) rebuild their entire view of the world by
 * replaying this topic from offset 0, so every field a consumer could need must be
 * carried here — an event that omits a field is a field that does not survive a
 * consumer restart.
 *
 * <p>Consumers own their own copy of this shape and deserialize by schema, not by Java
 * type; no {@code __TypeId__} header is sent. Fields may therefore be added freely, but
 * never renamed or removed without a consumer migration.
 */
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
    private String inputTopic;
    private String compensationTopic;

    public ServiceEvent() {}

    public ServiceEvent(Type type, String serviceName, String url, String status) {
        this.type = type;
        this.serviceName = serviceName;
        this.url = url;
        this.status = status;
        this.timestamp = Instant.now().toString();
    }

    /**
     * Builds an event carrying the service's full outward-facing state. Prefer this over
     * the constructor: it guarantees the Kafka destinations and fencing token travel with
     * every event, so a replaying consumer never has to fall back to an HTTP read.
     */
    public static ServiceEvent from(Type type, Service service) {
        ServiceEvent event = new ServiceEvent(type, service.getName(), service.getUrl(), service.getStatus());
        event.setOpenapiUrl(service.getOpenapiUrl());
        event.setInputTopic(service.getInputTopic());
        event.setCompensationTopic(service.getCompensationTopic());
        event.setGeneration(service.getStatusGeneration());
        return event;
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

    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }

    public String getCompensationTopic() { return compensationTopic; }
    public void setCompensationTopic(String compensationTopic) { this.compensationTopic = compensationTopic; }
}
