package com.eda.gateway.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps one bad record from taking down route propagation.
 *
 * <p>Paired with {@code ErrorHandlingDeserializer} in application.properties: a record
 * that cannot be deserialized (or a listener that throws) is retried twice, then
 * published to {@code service-events.DLT} and the offset is committed so the consumer
 * moves on. Without this the container re-polls the same offset forever and the
 * gateway's routing table stays permanently empty.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    /**
     * A producer dedicated to the dead-letter topic.
     *
     * <p>It exists because of what a failed record actually <em>is</em>: when
     * deserialization fails, the value handed to the recoverer is the raw
     * {@code byte[]} off the wire. Publishing that through the application's normal
     * {@code JsonSerializer} re-encodes it as a JSON string of Base64 — so the dead-letter
     * topic, whose entire purpose is letting a human see the malformed bytes, would
     * store something that is neither the original bytes nor readable.
     *
     * <p>{@link DelegatingByTypeSerializer} sends {@code byte[]} through untouched while
     * still JSON-serializing genuine objects (a listener that threw on a
     * well-formed record).
     */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        // LinkedHashMap: DelegatingByTypeSerializer matches in iteration order, so the
        // specific byte[] mapping must be considered before the Object catch-all.
        Map<Class<?>, org.apache.kafka.common.serialization.Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());

        ProducerFactory<String, Object> dltFactory = new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(),
                new StringSerializer(),
                new DelegatingByTypeSerializer(delegates));

        return new KafkaTemplate<>(dltFactory);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, Object> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new TopicPartition(record.topic() + ".DLT", record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }
}
