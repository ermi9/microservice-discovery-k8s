package com.eda.discovery.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka destination derivation.
 *
 * <p>This naming convention is part of the platform's outward contract: the choreography
 * layer computes nothing itself, it consumes {@code inputTopic} and
 * {@code compensationTopic} off the event. A change here is a breaking change to that
 * layer, which is exactly why it is pinned by a test.
 */
class TopicNamingStrategyTest {

    private TopicNamingStrategy naming;

    @BeforeEach
    void setUp() {
        naming = new TopicNamingStrategy();
        ReflectionTestUtils.setField(naming, "inputSuffix", ".in");
        ReflectionTestUtils.setField(naming, "compensationSuffix", ".compensate");
    }

    @Test
    @DisplayName("the documented convention: <name>.in and <name>.compensate")
    void convention() {
        assertThat(naming.inputTopicFor("order-service")).isEqualTo("order-service.in");
        assertThat(naming.compensationTopicFor("order-service")).isEqualTo("order-service.compensate");
    }

    @Test
    @DisplayName("input and compensation destinations are always distinct")
    void destinationsAreDistinct() {
        // A compensation must never be processable as forward work, which starts with
        // it never landing in the forward topic.
        assertThat(naming.inputTopicFor("svc")).isNotEqualTo(naming.compensationTopicFor("svc"));
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "order-service,   order-service.in",
            "order_service,   order_service.in",
            "order.service,   order.service.in",
            "Order-Service,   Order-Service.in",
            "svc123,          svc123.in"
    })
    @DisplayName("names already legal for Kafka pass through untouched")
    void legalNamesUnchanged(String serviceName, String expected) {
        assertThat(naming.inputTopicFor(serviceName)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "illegal char in \"{0}\" is normalised")
    @CsvSource({
            "'order service',  'order-service.in'",
            "'order/service',  'order-service.in'",
            "'order:service',  'order-service.in'",
            "'order@service',  'order-service.in'",
            "'order#1',        'order-1.in'"
    })
    @DisplayName("characters Kafka forbids in a topic name are replaced")
    void illegalCharactersNormalised(String serviceName, String expected) {
        assertThat(naming.inputTopicFor(serviceName)).isEqualTo(expected);
    }

    @Test
    @DisplayName("derived names contain only characters Kafka permits")
    void outputIsAlwaysKafkaLegal() {
        for (String name : new String[]{"a b", "a/b", "a:b", "héllo", "a b/c:d@e"}) {
            assertThat(naming.inputTopicFor(name)).matches("[a-zA-Z0-9._-]+");
            assertThat(naming.compensationTopicFor(name)).matches("[a-zA-Z0-9._-]+");
        }
    }

    @Test
    @DisplayName("suffixes are configurable, not hardcoded")
    void suffixesAreConfigurable() {
        ReflectionTestUtils.setField(naming, "inputSuffix", "-commands");
        ReflectionTestUtils.setField(naming, "compensationSuffix", "-undo");

        assertThat(naming.inputTopicFor("svc")).isEqualTo("svc-commands");
        assertThat(naming.compensationTopicFor("svc")).isEqualTo("svc-undo");
    }
}
