package com.eda.gateway.model;

/**
 * The gateway's own local view of the discovery change-stream event.
 *
 * <p>Deliberately a separate class from {@code com.eda.discovery.model.ServiceEvent}:
 * the contract between the two modules is the JSON <em>schema</em> on the
 * {@code service-events} topic, not a shared Java type. Neither module depends on the
 * other's classpath. Unknown fields are tolerated (Spring Kafka's JsonDeserializer
 * disables FAIL_ON_UNKNOWN_PROPERTIES), so the producer can add fields without
 * breaking this consumer.
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

    /**
     * Fencing token stamped by the partition leader that produced this event.
     * Monotonic per partition (Redis INCR on leadership change). Used to discard
     * events emitted by a deposed leader — see ServiceEventConsumer.
     */
    private long generation;

    /** Kafka destinations — consumed by the choreography layer, ignored by the gateway. */
    private String inputTopic;
    private String compensationTopic;

    public ServiceEvent() {}

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
