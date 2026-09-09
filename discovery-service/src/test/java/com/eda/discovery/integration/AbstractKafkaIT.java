package com.eda.discovery.integration;

import com.eda.discovery.kafka.KafkaProducerConfig;
import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.ServiceEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * One Kafka broker, started once per JVM and shared by every integration test.
 *
 * <p>Singleton rather than {@code @Container} per class: one broker for the whole suite
 * is faster and keeps the memory footprint low enough to run on a developer machine.
 * Ryuk tears it down when the JVM exits.
 *
 * <p>The image is pinned to the same {@code apache/kafka:3.7.0} the k8s manifests deploy,
 * rather than Testcontainers' default Confluent image, so the tests run against the
 * broker the platform actually deploys.
 */
public abstract class AbstractKafkaIT {

    protected static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));
        KAFKA.start();
    }

    protected static String bootstrap() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * Builds the publisher from the <em>real</em> production configuration classes, so
     * these tests exercise the shipped wire format rather than a re-declaration of it.
     * If {@code KafkaProducerConfig} regresses, these tests fail.
     */
    protected static ServiceEventPublisher realPublisher(String topic) {
        KafkaProducerConfig producerConfig = new KafkaProducerConfig();
        ReflectionTestUtils.setField(producerConfig, "bootstrapServers", bootstrap());

        KafkaTemplate<String, ServiceEvent> template =
                producerConfig.serviceEventKafkaTemplate(producerConfig.serviceEventProducerFactory());

        ServiceEventPublisher publisher = new ServiceEventPublisher();
        ReflectionTestUtils.setField(publisher, "kafkaTemplate", template);
        ReflectionTestUtils.setField(publisher, "topic", topic);
        return publisher;
    }

    /** Creates a topic using the real {@code KafkaTopicConfig} declaration. */
    protected static NewTopic createRealTopic(String topicName, int partitions) throws Exception {
        com.eda.discovery.kafka.KafkaTopicConfig topicConfig =
                new com.eda.discovery.kafka.KafkaTopicConfig();
        ReflectionTestUtils.setField(topicConfig, "topicName", topicName);
        ReflectionTestUtils.setField(topicConfig, "partitions", partitions);

        NewTopic newTopic = topicConfig.serviceEventsTopic();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(newTopic)).all().get();
        }
        return newTopic;
    }

    protected static Admin admin() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        return Admin.create(cfg);
    }

    /** A consumer with a fresh group id, so every call replays the topic from offset 0. */
    protected static <V> KafkaConsumer<String, V> freshConsumer(Properties overrides) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + java.util.UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.putAll(overrides);
        return new KafkaConsumer<>(props);
    }
}
