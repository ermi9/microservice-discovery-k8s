package com.eda.discovery.integration;

import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.model.ServiceStatus;
import com.example.choreography.ForeignServiceEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire independence: schema-only coupling.
 *
 * <p>The property: a consumer holding only its own DTO can deserialize every
 * {@code service-events} record, with no discovery-service class involved. The adversary
 * is not a runtime fault — it is a config edit reintroducing type headers, which is why
 * this is asserted structurally on the record itself. {@code JsonSerializer} stamps
 * {@code __TypeId__} unless told not to, and {@code JsonDeserializer} gives that header
 * precedence over a consumer's configured default type, so a consumer without this
 * module on its classpath would fail deserialization and stall on the offset.
 *
 * <p>Scope: this runs in one JVM, so {@link ForeignServiceEvent} is technically on the
 * same classpath as the producer's type. The isolation is enforced instead by
 * (a) asserting no {@code __TypeId__} header exists on the wire at all, and
 * (b) deserializing with a <em>naive default-configured</em> consumer.
 */
class WireContractIT extends AbstractKafkaIT {

    private static final String TOPIC = "wire-contract-events";
    private static ServiceEventPublisher publisher;

    @BeforeAll
    static void setUp() throws Exception {
        createRealTopic(TOPIC, 3);
        publisher = realPublisher(TOPIC);
    }

    private static ServiceEvent sampleEvent() {
        Service service = new Service("order-service", "http://order:8080", "http://order:8080/v3/api-docs");
        service.setStatus(ServiceStatus.HEALTHY);
        service.setStatusGeneration(7);
        service.setInputTopic("order-service.in");
        service.setCompensationTopic("order-service.compensate");
        return ServiceEvent.from(ServiceEvent.Type.SERVICE_REGISTERED, service);
    }

    @Test
    @DisplayName("no __TypeId__ header is written to the wire")
    void noTypeHeaderOnTheWire() {
        publisher.publish(sampleEvent());

        Properties p = new Properties();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        try (KafkaConsumer<String, byte[]> consumer = freshConsumer(p)) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecord<String, byte[]> record = pollOne(consumer);

            List<String> headers = new ArrayList<>();
            for (Header h : record.headers()) headers.add(h.key());

            assertThat(headers)
                    .as("a type header would recouple every consumer to discovery internals")
                    .doesNotContain("__TypeId__");

            // The payload must be plain, self-describing JSON.
            String json = new String(record.value());
            assertThat(json).contains("\"serviceName\":\"order-service\"");
            assertThat(json).doesNotContain("com.eda.discovery");
        }
    }

    @Test
    @DisplayName("a naive consumer with DEFAULT type-header settings can still deserialize")
    void naiveConsumerCanDeserialize() {
        publisher.publish(sampleEvent());

        // Deliberately NOT setting use.type.headers=false — a consumer configured
        // straight out of the box. With type info on the wire this would fail with
        // ClassNotFoundException; with the contract honoured it just works.
        Properties p = new Properties();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        p.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ForeignServiceEvent.class.getName());
        p.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.choreography");

        try (KafkaConsumer<String, ForeignServiceEvent> consumer = freshConsumer(p)) {
            consumer.subscribe(List.of(TOPIC));
            ForeignServiceEvent event = pollOne(consumer).value();

            assertThat(event).isNotNull();
            assertThat(event.getServiceName()).isEqualTo("order-service");
            assertThat(event.getType()).isEqualTo("SERVICE_REGISTERED");
            assertThat(event.getUrl()).isEqualTo("http://order:8080");
            assertThat(event.getStatus()).isEqualTo("healthy");
        }
    }

    @Test
    @DisplayName("the choreography's contract fields survive the round trip")
    void contractFieldsSurviveRoundTrip() {
        publisher.publish(sampleEvent());

        Properties p = new Properties();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        p.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ForeignServiceEvent.class.getName());
        p.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.choreography");

        try (KafkaConsumer<String, ForeignServiceEvent> consumer = freshConsumer(p)) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecord<String, ForeignServiceEvent> record = pollOne(consumer);
            ForeignServiceEvent event = record.value();

            // The destinations the choreography layer routes on.
            assertThat(event.getInputTopic()).isEqualTo("order-service.in");
            assertThat(event.getCompensationTopic()).isEqualTo("order-service.compensate");
            // The fencing token must reach the consumer to be usable.
            assertThat(event.getGeneration()).isEqualTo(7);
            // Keyed by service name, so per-service order is guaranteed.
            assertThat(record.key()).isEqualTo("order-service");
        }
    }

    private static <V> ConsumerRecord<String, V> pollOne(KafkaConsumer<String, V> consumer) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(500));
            var it = StreamSupport.stream(records.spliterator(), false).findFirst();
            if (it.isPresent()) return it.get();
        }
        throw new AssertionError("no record arrived within 30s");
    }
}
