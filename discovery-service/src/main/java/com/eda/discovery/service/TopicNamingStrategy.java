package com.eda.discovery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a logical service name into its Kafka destinations.
 *
 * <p>An event-driven consumer needs to know what topic a service consumes from and where
 * an undo for it goes — questions a base URL cannot answer. These two derivations are
 * what let the registry answer them.
 *
 * <p><b>Convention over advertisement.</b> Topics are derived centrally from the service
 * name rather than declared by each service in its registration payload. The trade-off:
 * services need no code change and cannot advertise a topic they do not actually consume,
 * but the naming convention becomes part of the platform contract and a service must
 * follow it. The alternative — an explicit {@code inputTopic} field in
 * {@code POST /register} — is more flexible and is the natural upgrade path if a service
 * ever needs a topic that does not match its name; the registry already carries the
 * fields, so only the controller and this class would change.
 */
@Component
public class TopicNamingStrategy {

    @Value("${topics.input.suffix:.in}")
    private String inputSuffix;

    @Value("${topics.compensation.suffix:.compensate}")
    private String compensationSuffix;

    public String inputTopicFor(String serviceName) {
        return normalize(serviceName) + inputSuffix;
    }

    /**
     * A dedicated compensation topic rather than a flag on the input topic. Costs one
     * topic per service, but keeps each service's forward consumer and undo consumer
     * as separate listeners — a compensation cannot be accidentally processed as
     * forward work because it never lands in the forward topic.
     */
    public String compensationTopicFor(String serviceName) {
        return normalize(serviceName) + compensationSuffix;
    }

    /** Kafka topic names allow [a-zA-Z0-9._-] only. */
    private String normalize(String serviceName) {
        return serviceName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
