package com.eda.discovery.kafka;

import com.eda.discovery.model.ServiceEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Explicit producer for the {@code service-events} change stream.
 *
 * <p>{@code ServiceEventPublisher} injects a {@code KafkaTemplate<String, ServiceEvent>},
 * and Spring Boot's auto-configured template is not declared with that parameterization,
 * so it does not qualify as an autowire candidate. Without the template declared here the
 * context fails to start with {@code NoSuchBeanDefinitionException}.
 *
 * <p>Declaring the factory here also makes the wire format structural rather than a
 * property that can be lost in a config edit: {@code addTypeInfo=false} is set on the
 * serializer itself. With type info on, every record carries
 * {@code __TypeId__=com.eda.discovery.model.ServiceEvent}; that header outranks a
 * consumer's configured default type, so any consumer without this module on its
 * classpath — the gateway, and any future choreography resolver — fails deserialization
 * and stalls on the same offset forever. The event stream is a JSON schema contract;
 * consumers own their own DTOs.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, ServiceEvent> serviceEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Retry and wait for full ISR acknowledgement: a dropped registration event is a
        // service that never appears in any consumer's table until it re-registers.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        JsonSerializer<ServiceEvent> valueSerializer = new JsonSerializer<>();
        valueSerializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, ServiceEvent> serviceEventKafkaTemplate(
            ProducerFactory<String, ServiceEvent> serviceEventProducerFactory) {
        return new KafkaTemplate<>(serviceEventProducerFactory);
    }
}
