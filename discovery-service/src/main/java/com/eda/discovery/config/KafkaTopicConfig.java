package com.eda.discovery.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@code service-events} topic with an explicit partition count so the routing
 * event stream is provisioned deterministically instead of relying on broker auto-creation
 * (which defaults to a single partition).
 *
 * <p>Events are published keyed by service name, so all events for a given service always land
 * on the same partition and are therefore consumed in order, while distinct services spread
 * across the partitions for parallel consumption.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String SERVICE_EVENTS_TOPIC = "service-events";

    @Value("${kafka.topic.partitions:3}")
    private int partitions;

    @Value("${kafka.topic.replicas:1}")
    private short replicas;

    @Bean
    public NewTopic serviceEventsTopic() {
        return TopicBuilder.name(SERVICE_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
