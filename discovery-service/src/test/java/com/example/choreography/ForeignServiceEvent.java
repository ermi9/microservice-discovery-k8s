package com.example.choreography;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A deliberately foreign DTO, standing in for the choreography layer's own model.
 *
 * <p>It lives outside {@code com.eda.discovery} on purpose: nothing here references a
 * discovery-service class, and the field set is intentionally a <em>subset</em> of the
 * producer's, to prove the contract is the JSON schema rather than a shared Java type
 * and that unknown fields are tolerated (additive evolution).
 *
 * <p>Note the class name differs from the producer's too — if a {@code __TypeId__}
 * header were present and honoured, deserialization into this type would fail.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ForeignServiceEvent {

    private String type;
    private String serviceName;
    private String url;
    private String status;
    private long generation;
    private String inputTopic;
    private String compensationTopic;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }

    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }

    public String getCompensationTopic() { return compensationTopic; }
    public void setCompensationTopic(String compensationTopic) { this.compensationTopic = compensationTopic; }
}
