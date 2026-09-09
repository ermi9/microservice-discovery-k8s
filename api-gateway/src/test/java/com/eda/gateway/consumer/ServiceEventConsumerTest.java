package com.eda.gateway.consumer;

import com.eda.gateway.model.ServiceEvent;
import com.eda.gateway.registry.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generation fencing and the status allowlist — the two behaviours the choreography
 * layer inherits from this consumer.
 *
 * <p>Both are exercised as pure logic: a stale event is <em>synthesised</em> rather than
 * produced by a real leadership move, which is what makes them testable without a
 * cluster. Behaviour under a real partition-leader failover is not covered here.
 *
 * <p>The real {@link RouteRegistry} is used rather than a mock: it is an in-memory map,
 * and asserting against real state is stronger than asserting a call happened.
 */
class ServiceEventConsumerTest {

    private ServiceEventConsumer consumer;
    private RouteRegistry routeRegistry;
    private RouteDefinitionWriter routeDefinitionWriter;

    @BeforeEach
    void setUp() {
        routeRegistry = new RouteRegistry();
        routeDefinitionWriter = Mockito.mock(RouteDefinitionWriter.class);
        Mockito.when(routeDefinitionWriter.save(Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(routeDefinitionWriter.delete(Mockito.any())).thenReturn(Mono.empty());

        consumer = new ServiceEventConsumer();
        ReflectionTestUtils.setField(consumer, "routeDefinitionWriter", routeDefinitionWriter);
        ReflectionTestUtils.setField(consumer, "eventPublisher",
                Mockito.mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(consumer, "routeRegistry", routeRegistry);
    }

    private static ServiceEvent event(ServiceEvent.Type type, String name, String status, long generation) {
        ServiceEvent e = new ServiceEvent();
        e.setType(type);
        e.setServiceName(name);
        e.setStatus(status);
        e.setGeneration(generation);
        e.setUrl("http://" + name + ":8080");
        e.setOpenapiUrl("http://" + name + ":8080/v3/api-docs");
        return e;
    }

    private boolean routed(String name) {
        return routeRegistry.get(name) != null;
    }

    // ------------------------------------------------------------------
    // Generation fencing under at-least-once delivery
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("rejects duplicate, reordered and stale-generation events")
    class Fencing {

        @Test
        @DisplayName("the full adversarial sequence: gen5, dup gen5, stale gen3, fresh gen6, re-register")
        void fullSequence() {
            // Registration establishes the fence and routes the service.
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 5));
            assertThat(routed("svc")).as("routed on registration").isTrue();

            // A newer generation legitimately tears the route down.
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "unavailable", 6));
            assertThat(routed("svc")).as("gen 6 applied").isFalse();

            // A stale event from a deposed leader must NOT resurrect the route.
            // This is the exact failure the fence exists to prevent.
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "healthy", 3));
            assertThat(routed("svc")).as("stale gen 3 dropped, route stays down").isFalse();

            // Re-registration is a new lifecycle, not a stale event: the fence resets
            // even though generation 0 is far below the 6 already seen.
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 0));
            assertThat(routed("svc")).as("re-registration resets the fence").isTrue();
        }

        @Test
        @DisplayName("a duplicate at the same generation is idempotent")
        void duplicateIsIdempotent() {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 4));
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "unavailable", 5));
            assertThat(routed("svc")).isFalse();

            // Same record delivered twice — at-least-once. State must not change.
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "unavailable", 5));
            assertThat(routed("svc")).as("redelivery changes nothing").isFalse();
        }

        @Test
        @DisplayName("equal generation is applied, strictly-lower is dropped (boundary)")
        void boundaryIsInclusive() {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 7));
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "unavailable", 7));
            assertThat(routed("svc")).as("gen == seen is applied, not fenced").isFalse();

            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "healthy", 6));
            assertThat(routed("svc")).as("gen < seen is fenced").isFalse();
        }

        @Test
        @DisplayName("the fence is per-service, not global")
        void fenceIsPerService() {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc-a", "healthy", 9));
            // svc-b's low generation must not be judged against svc-a's fence.
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc-b", "healthy", 1));
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc-b", "unavailable", 2));

            assertThat(routed("svc-a")).as("svc-a untouched").isTrue();
            assertThat(routed("svc-b")).as("svc-b's own fence applied").isFalse();
        }

        @Test
        @DisplayName("deregistration clears the fence so a later re-registration is not blocked")
        void deregistrationClearsFence() {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 8));
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_DEREGISTERED, "svc", "unavailable", 9));
            assertThat(routed("svc")).as("deregistered").isFalse();

            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 0));
            assertThat(routed("svc")).as("fresh instance at gen 0 is routable again").isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Status allowlist
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("only 'healthy' is routable (allowlist, not blocklist)")
    class StatusAllowlist {

        @ParameterizedTest(name = "status \"{0}\" -> routable={1}")
        @CsvSource({
                "healthy,      true",
                "not-ready,    false",
                "unavailable,  false",
                "unknown,      false"
        })
        @DisplayName("the platform vocabulary maps to exactly one routable value")
        void vocabulary(String status, boolean expectRoutable) {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 1));
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", status, 2));
            assertThat(routed("svc")).isEqualTo(expectRoutable);
        }

        /**
         * The regression this guards: the previous blocklist excluded only "unavailable"
         * and "unhealthy", so "not-ready" — and any status added upstream later — passed
         * the filter and kept receiving traffic.
         */
        @ParameterizedTest(name = "unrecognised status \"{0}\" is not routable")
        @ValueSource(strings = {"degraded", "unhealthy", "draining", "SOMETHING_NEW", "Healthy", "HEALTHY", ""})
        @DisplayName("an unrecognised or differently-cased status is never routable")
        void unknownStatusIsNotRoutable(String status) {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 1));
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", status, 2));
            assertThat(routed("svc")).isFalse();
        }

        @Test
        @DisplayName("a healthy status restores a torn-down route")
        void recoveryRestoresRoute() {
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 1));
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "not-ready", 2));
            assertThat(routed("svc")).isFalse();

            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, "svc", "healthy", 3));
            assertThat(routed("svc")).as("service recovered").isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Malformed input — the consumer must never throw
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("a malformed event is ignored, never thrown")
    class Malformed {

        @Test
        @DisplayName("null event, null name and null type are all survivable")
        void nullsAreSurvivable() {
            consumer.onServiceEvent(null);
            consumer.onServiceEvent(event(ServiceEvent.Type.STATUS_CHANGED, null, "healthy", 1));

            ServiceEvent noType = event(ServiceEvent.Type.STATUS_CHANGED, "svc", "healthy", 1);
            noType.setType(null);
            consumer.onServiceEvent(noType);

            // and the consumer still works afterwards
            consumer.onServiceEvent(event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 1));
            assertThat(routed("svc")).as("consumer still functional after malformed input").isTrue();
        }

        @Test
        @DisplayName("a registration with no url does not create a route")
        void registrationWithoutUrlIsNotRouted() {
            ServiceEvent e = event(ServiceEvent.Type.SERVICE_REGISTERED, "svc", "healthy", 1);
            e.setUrl(null);
            consumer.onServiceEvent(e);

            Mockito.verify(routeDefinitionWriter, Mockito.never())
                    .save(Mockito.<Mono<RouteDefinition>>any());
        }
    }
}
