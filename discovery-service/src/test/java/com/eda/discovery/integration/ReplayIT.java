package com.eda.discovery.integration;

import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.model.ServiceStatus;
import com.example.choreography.ForeignServiceEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replayability over the durable log.
 *
 * <p>The property: a fresh consumer replaying {@code service-events} from offset 0
 * reconstructs the exact current resolution state, including that deregistered services
 * are absent. This is the cold-start correctness the choreography layer's resolution
 * table depends on, and it rests on two things this test therefore exercises together —
 * the topic being compacted rather than retention-deleted, and deregistration being an
 * explicit removal event rather than a status flip.
 *
 * <p>The table is rebuilt through {@link ForeignServiceEvent} — a foreign DTO — because
 * "a consumer can rebuild state from the log" is only a real guarantee if a consumer that
 * is not this module can do it.
 */
class ReplayIT extends AbstractKafkaIT {

    private static final String TOPIC = "replay-events";
    private static ServiceEventPublisher publisher;

    @BeforeAll
    static void publishHistory() throws Exception {
        createRealTopic(TOPIC, 3);
        publisher = realPublisher(TOPIC);

        // A history with every lifecycle shape in it: plain registration, a status
        // transition, a removal, and a re-registration at a new address.
        publisher.publish(registered("order-service", "http://order:8080"));
        publisher.publish(registered("inventory-service", "http://inventory:8081"));
        publisher.publish(registered("payment-service", "http://payment:8082"));

        publisher.publish(statusChanged("order-service", "http://order:8080", ServiceStatus.NOT_READY, 1));

        publisher.publish(deregistered("inventory-service", "http://inventory:8081"));

        // order-service restarts at a new address.
        publisher.publish(registered("order-service", "http://order:9090"));

        Thread.sleep(1000); // let the producer flush before any replay begins
    }

    private static ServiceEvent registered(String name, String url) {
        Service s = new Service(name, url, url + "/v3/api-docs");
        s.setInputTopic(name + ".in");
        s.setCompensationTopic(name + ".compensate");
        return ServiceEvent.from(ServiceEvent.Type.SERVICE_REGISTERED, s);
    }

    private static ServiceEvent statusChanged(String name, String url, String status, long generation) {
        Service s = new Service(name, url, url + "/v3/api-docs");
        s.setStatus(status);
        s.setStatusGeneration(generation);
        return ServiceEvent.from(ServiceEvent.Type.STATUS_CHANGED, s);
    }

    private static ServiceEvent deregistered(String name, String url) {
        Service s = new Service(name, url, url + "/v3/api-docs");
        return ServiceEvent.from(ServiceEvent.Type.SERVICE_DEREGISTERED, s);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the topic is declared compacted and keyed, not auto-created with delete retention")
    void topicIsCompacted() throws Exception {
        try (Admin admin = admin()) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC);
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);

            assertThat(config.get(TopicConfig.CLEANUP_POLICY_CONFIG).value())
                    .as("delete retention would silently drop services from every consumer's cold start")
                    .isEqualTo(TopicConfig.CLEANUP_POLICY_COMPACT);

            TopicDescription description =
                    admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC);
            assertThat(description.partitions()).hasSize(3);
        }
    }

    @Test
    @DisplayName("a fresh consumer group rebuilds the exact current state from offset 0")
    void replayRebuildsState() {
        Map<String, ForeignServiceEvent> table = replayTable();

        assertThat(table.keySet())
                .as("deregistered services must not appear in a rebuilt table")
                .containsExactlyInAnyOrder("order-service", "payment-service");

        assertThat(table.get("order-service").getUrl())
                .as("the re-registration at a new address must win")
                .isEqualTo("http://order:9090");

        assertThat(table.get("payment-service").getUrl()).isEqualTo("http://payment:8082");
        assertThat(table.get("order-service").getInputTopic()).isEqualTo("order-service.in");
    }

    @Test
    @DisplayName("replay is deterministic: two independent consumer groups agree")
    void replayIsDeterministic() {
        assertThat(keysOf(replayTable()))
                .as("two cold starts of the same log must produce the same table")
                .isEqualTo(keysOf(replayTable()));
    }

    @Test
    @DisplayName("per-service ordering holds: events for one key arrive in publish order")
    void perServiceOrderingHolds() {
        List<ForeignServiceEvent> orderServiceEvents = new ArrayList<>();
        for (ConsumerRecord<String, ForeignServiceEvent> record : replayRecords()) {
            if ("order-service".equals(record.key())) orderServiceEvents.add(record.value());
        }

        // Keying by service name puts all of a service's events on one partition, which
        // is what makes "a DEREGISTERED never overtakes its REGISTERED" true.
        assertThat(orderServiceEvents).extracting(ForeignServiceEvent::getType)
                .containsExactly("SERVICE_REGISTERED", "STATUS_CHANGED", "SERVICE_REGISTERED");
        assertThat(orderServiceEvents.get(2).getUrl()).isEqualTo("http://order:9090");
    }

    // ------------------------------------------------------------------

    private static Set<String> keysOf(Map<String, ForeignServiceEvent> table) {
        return new TreeSet<>(table.keySet());
    }

    /** Folds the whole log into a resolution table, the way a choreography consumer would. */
    private static Map<String, ForeignServiceEvent> replayTable() {
        Map<String, ForeignServiceEvent> table = new LinkedHashMap<>();
        for (ConsumerRecord<String, ForeignServiceEvent> record : replayRecords()) {
            ForeignServiceEvent event = record.value();
            if ("SERVICE_DEREGISTERED".equals(event.getType())) {
                table.remove(event.getServiceName());
            } else {
                table.put(event.getServiceName(), event);
            }
        }
        return table;
    }

    private static List<ConsumerRecord<String, ForeignServiceEvent>> replayRecords() {
        Properties p = new Properties();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        p.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ForeignServiceEvent.class.getName());
        p.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.choreography");

        List<ConsumerRecord<String, ForeignServiceEvent>> all = new ArrayList<>();
        try (KafkaConsumer<String, ForeignServiceEvent> consumer = freshConsumer(p)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 30_000;
            int emptyPolls = 0;
            while (System.currentTimeMillis() < deadline && emptyPolls < 3) {
                ConsumerRecords<String, ForeignServiceEvent> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    if (!all.isEmpty()) emptyPolls++;
                } else {
                    emptyPolls = 0;
                    records.forEach(all::add);
                }
            }
        }
        return all;
    }
}
