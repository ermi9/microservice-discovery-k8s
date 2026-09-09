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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Propagation latency: status-change published → applied at an independent consumer,
 * reported as a distribution.
 *
 * <p><b>What this measures, precisely:</b> the interval from {@code ServiceEventPublisher
 * .publish()} being called to a separate consumer having deserialized the record into its
 * own DTO. It does <em>not</em> include the Kubernetes Watch's own detection delay, nor
 * the consumer's downstream application of the change. It is the transport component of
 * staleness, and should be quoted as such.
 *
 * <p><b>Setup that the number is only valid for:</b> a single broker in a container on
 * the same host, 3 partitions, one consumer, no competing load, acks=all with
 * idempotence. A multi-broker cluster with real network hops will differ. The methodology
 * is reported alongside the figures for exactly this reason.
 *
 * <p>The assertion is deliberately loose — this is a measurement, not a threshold. A
 * tight bound here would be a flaky test that says nothing about the system.
 */
class PropagationLatencyIT extends AbstractKafkaIT {

    private static final String TOPIC = "latency-events";
    private static final int WARMUP = 50;
    private static final int SAMPLES = 1000;
    /** Gap between publishes, so the consumer is never backlogged. See the loop below. */
    private static final long PACING_MS = 10;

    @Test
    @DisplayName("measure publish→consumer-applied latency and record the distribution")
    void measurePropagationLatency() throws Exception {
        createRealTopic(TOPIC, 3);
        ServiceEventPublisher publisher = realPublisher(TOPIC);

        Map<String, Long> publishedAt = new ConcurrentHashMap<>();
        List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(WARMUP + SAMPLES);

        Thread consumerThread = new Thread(() -> consume(publishedAt, latenciesMicros, done));
        consumerThread.setDaemon(true);
        consumerThread.start();

        // Let the consumer join the group and get its assignment before timing anything;
        // otherwise the first samples measure rebalancing, not propagation.
        Thread.sleep(5_000);

        // Paced, not a tight loop. Publishing as fast as possible backlogs the consumer,
        // so each sample would include the queueing delay of everything ahead of it -- that
        // measures saturated throughput, not staleness. Staleness is "how long after a
        // change does a consumer learn it" under normal conditions, so the publisher is
        // paced to keep the consumer's queue effectively empty.
        for (int i = 0; i < WARMUP + SAMPLES; i++) {
            String name = "svc-" + i;
            publishedAt.put(name, System.nanoTime());
            publisher.publish(statusEvent(name, i));
            Thread.sleep(PACING_MS);
        }

        boolean complete = done.await(180, TimeUnit.SECONDS);
        assertThat(complete)
                .as("all %d events must reach the consumer", WARMUP + SAMPLES)
                .isTrue();

        // Discard warmup: the first records also pay JIT and connection setup.
        List<Long> measured = new ArrayList<>(latenciesMicros.subList(
                Math.min(WARMUP, latenciesMicros.size()), latenciesMicros.size()));
        Collections.sort(measured);

        double p50 = percentile(measured, 50) / 1000.0;
        double p95 = percentile(measured, 95) / 1000.0;
        double p99 = percentile(measured, 99) / 1000.0;
        double min = measured.get(0) / 1000.0;
        double max = measured.get(measured.size() - 1) / 1000.0;
        double mean = measured.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;

        String report = """
                M1 — propagation latency (publish -> applied at an independent consumer)

                  samples   %d (after %d discarded warmup)
                  min       %.2f ms
                  p50       %.2f ms
                  p95       %.2f ms
                  p99       %.2f ms
                  max       %.2f ms
                  mean      %.2f ms

                Setup: single-broker apache/kafka:3.7.0 in a container on the same host,
                3 partitions, 1 consumer, acks=all, enable.idempotence=true, no competing
                load, publishes paced %d ms apart so the consumer is never backlogged.
                Measures transport only: publish() -> consumer deserialised. Excludes
                Kubernetes Watch detection delay and any downstream application of the change.

                This is an unsaturated (open-loop) measurement of staleness. It is NOT a
                throughput benchmark, and the figures must not be quoted as one.
                """.formatted(measured.size(), WARMUP, min, p50, p95, p99, max, mean, PACING_MS);

        System.out.println(report);
        writeReport(report);

        // Sanity bound only. The deliverable is the distribution above, not this assertion.
        assertThat(p99).as("p99 propagation latency in ms").isLessThan(10_000.0);
    }

    private static ServiceEvent statusEvent(String name, int generation) {
        Service s = new Service(name, "http://" + name + ":8080", "http://" + name + ":8080/docs");
        s.setStatus(ServiceStatus.HEALTHY);
        s.setStatusGeneration(generation);
        return ServiceEvent.from(ServiceEvent.Type.STATUS_CHANGED, s);
    }

    private static void consume(Map<String, Long> publishedAt, List<Long> latencies, CountDownLatch done) {
        Properties p = new Properties();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        p.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ForeignServiceEvent.class.getName());
        p.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.choreography");

        try (KafkaConsumer<String, ForeignServiceEvent> consumer = freshConsumer(p)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 200_000;
            while (System.currentTimeMillis() < deadline && done.getCount() > 0) {
                ConsumerRecords<String, ForeignServiceEvent> records = consumer.poll(Duration.ofMillis(100));
                long now = System.nanoTime();
                for (ConsumerRecord<String, ForeignServiceEvent> record : records) {
                    Long sent = publishedAt.get(record.value().getServiceName());
                    if (sent != null) {
                        latencies.add((now - sent) / 1_000); // microseconds
                        done.countDown();
                    }
                }
            }
        }
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void writeReport(String report) {
        try {
            Path out = Path.of("target", "M1-propagation-latency.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);
            System.out.println("[M1] report written to " + out.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[M1] could not write report: " + e.getMessage());
        }
    }
}
