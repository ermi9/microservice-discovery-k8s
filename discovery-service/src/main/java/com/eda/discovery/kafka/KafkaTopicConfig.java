package com.eda.discovery.kafka;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

import java.util.Map;

/**
 * Declares the {@code service-events} topic explicitly instead of relying on Kafka
 * broker auto-creation.
 *
 * <p>Auto-creation yields a single-partition topic with {@code cleanup.policy=delete} and
 * the broker default retention, which is wrong for a change stream: consumers (the
 * gateway, and any choreography resolver) rebuild their entire routing/resolution table
 * by replaying this topic from offset 0, so a record aged out of the log is a service
 * that silently vanishes from every consumer's table on the next cold start.
 *
 * <p>{@code cleanup.policy=compact} keyed by service name guarantees the last event per
 * service survives forever — which is exactly the "current state of the registry"
 * that a replaying consumer needs. Deregistration is carried as a
 * {@code SERVICE_DEREGISTERED} event (not a null tombstone) so replaying consumers
 * see an explicit removal instruction rather than an absence.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${service-events.topic.name:service-events}")
    private String topicName;

    @Value("${service-events.topic.partitions:3}")
    private int partitions;

    @Bean
    public NewTopic serviceEventsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(partitions)
                .replicas(1)
                .configs(Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,
                        // Compact aggressively so a replaying consumer converges quickly.
                        TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1",
                        TopicConfig.SEGMENT_MS_CONFIG, "60000"))
                .build();
    }
}
