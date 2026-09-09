package com.eda.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context test for the gateway.
 *
 * <p>Its job is bean-wiring, not behaviour: {@code KafkaErrorHandlingConfig} declares a
 * second {@code KafkaTemplate<String, Object>} alongside Boot's auto-configured one, and
 * an ambiguous injection point would only surface at runtime — as a pod that crash-loops
 * rather than a test that fails.
 *
 * <p>Listeners are stopped and the broker address is a dummy: this test asserts the
 * context can be built, deliberately without requiring Kafka to exist.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:59092",
        "spring.kafka.listener.auto-startup=false"
})
class GatewayContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the application context starts with both Kafka templates declared")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("the dead-letter error handler resolves unambiguously")
    void errorHandlerIsWired() {
        assertThat(context.getBean(DefaultErrorHandler.class)).isNotNull();
        assertThat(context.getBean("deadLetterKafkaTemplate")).isNotNull();
    }
}
