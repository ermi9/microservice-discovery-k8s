package com.eda.gateway.integration;

import com.eda.gateway.config.KafkaErrorHandlingConfig;
import com.eda.gateway.model.ServiceEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Poison-message resilience.
 *
 * <p>The property: a single malformed record does not stall the consumer; it is diverted
 * to {@code <topic>.DLT} and processing continues.
 *
 * <p>Why this needs a real broker: deserialization happens inside {@code poll()}, before
 * any container-level error handler can see the record, so a poison pill would otherwise
 * make the container re-poll the same offset forever and leave the gateway's routing
 * table permanently empty. Only an end-to-end run through a real consumer shows that
 * {@code ErrorHandlingDeserializer} plus {@code DefaultErrorHandler} break that loop.
 *
 * <p>The listener is wired from the real {@link KafkaErrorHandlingConfig} and from the
 * deserializer settings in {@code application.properties}, so this fails if either
 * regresses.
 */
class PoisonMessageIT {

    private static final String TOPIC = "poison-events";
    private static final String DLT = TOPIC + ".DLT";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    private static KafkaMessageListenerContainer<String, ServiceEvent> container;
    private static final List<ServiceEvent> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startEverything() {
        KAFKA.start();

        // Producer that speaks the platform wire format (no type headers).
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>();
        valueSerializer.setAddTypeInfo(false);
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                        new StringSerializer(), valueSerializer));

        // Consumer configured exactly as api-gateway/application.properties configures it.
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "poison-test-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        consumerProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ServiceEvent.class.getName());
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.eda.gateway.model");

        ContainerProperties containerProps = new ContainerProperties(TOPIC);
        containerProps.setMessageListener((MessageListener<String, ServiceEvent>) r -> received.add(r.value()));

        container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, ServiceEvent>(consumerProps), containerProps);
        // The real production error handler.
        KafkaErrorHandlingConfig errorConfig = new KafkaErrorHandlingConfig();
        container.setCommonErrorHandler(errorConfig.kafkaErrorHandler(
                errorConfig.deadLetterKafkaTemplate(new DefaultKafkaProducerFactory<>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                        new StringSerializer(), new JsonSerializer<>()))));
        container.start();

        // valid, POISON, valid — the poison record sits between two good ones, so a stall
        // would be visible as the third event never arriving.
        template.send(TOPIC, "svc-before", event("svc-before"));
        sendRaw(KAFKA.getBootstrapServers(), TOPIC, "poison", "{ this is not valid json ".getBytes());
        template.send(TOPIC, "svc-after", event("svc-after"));
        template.flush();
    }

    @AfterAll
    static void stopEverything() {
        if (container != null) container.stop();
        KAFKA.stop();
    }

    private static ServiceEvent event(String name) {
        ServiceEvent e = new ServiceEvent();
        e.setType(ServiceEvent.Type.SERVICE_REGISTERED);
        e.setServiceName(name);
        e.setUrl("http://" + name + ":8080");
        e.setStatus("healthy");
        e.setGeneration(1);
        return e;
    }

    private static void sendRaw(String bootstrap, String topic, String key, byte[] value) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<>(
                props, new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, key, value));
            producer.flush();
        }
    }

    @Test
    @DisplayName("valid records on both sides of a poison record are still processed")
    void consumerSurvivesPoisonRecord() {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(received).extracting(ServiceEvent::getServiceName)
                        .contains("svc-before", "svc-after"));

        // The critical assertion: the record AFTER the poison pill arrived, so the
        // container advanced past the poison offset instead of re-polling it.
        assertThat(received).extracting(ServiceEvent::getServiceName)
                .as("consumer advanced past the poison offset")
                .containsExactly("svc-before", "svc-after");
    }

    @Test
    @DisplayName("the unparseable record is diverted to <topic>.DLT")
    void poisonRecordLandsInDlt() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        List<String> dltPayloads = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(DLT));
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(2));
                dltPayloads.clear();
                for (ConsumerRecord<String, byte[]> r : records) dltPayloads.add(new String(r.value()));
                assertThat(dltPayloads).isNotEmpty();
            }
        });

        assertThat(dltPayloads)
                .as("the original bytes are preserved for inspection")
                .anyMatch(s -> s.contains("this is not valid json"));
    }
}
