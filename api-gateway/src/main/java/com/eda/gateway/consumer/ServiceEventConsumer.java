package com.eda.gateway.consumer;

import com.eda.gateway.model.RouteInfo;
import com.eda.gateway.model.ServiceEvent;
import com.eda.gateway.registry.RouteRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ServiceEventConsumer {

    /**
     * The single status value that means "send traffic here". Everything else —
     * not-ready, unavailable, unknown, or any value added upstream in future —
     * means "don't". An allowlist on purpose: a blocklist would let any status the
     * gateway has not been taught about through as routable.
     */
    private static final String ROUTABLE_STATUS = "healthy";

    @Autowired
    private RouteDefinitionWriter routeDefinitionWriter;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private RouteRegistry routeRegistry;

    /**
     * Highest generation applied per service. Kafka is at-least-once and leadership
     * can move mid-flight, so a status event produced by a deposed partition leader
     * can arrive after a newer one from its successor. Applying it would flap the
     * route — a late "unavailable" from an old leader tearing down a route that the
     * new leader has already reported healthy.
     */
    private final Map<String, Long> lastGeneration = new ConcurrentHashMap<>();

    @KafkaListener(topics = "service-events", groupId = "api-gateway-group")
    public void onServiceEvent(ServiceEvent event) {
        if (event == null || event.getServiceName() == null || event.getType() == null) {
            System.err.println("[Gateway] Ignoring malformed event: " + event);
            return;
        }

        System.out.println("[Gateway] Received event: " + event.getType()
                + " for " + event.getServiceName() + " (gen=" + event.getGeneration() + ")");

        switch (event.getType()) {
            // Registration and deregistration are lifecycle boundaries, not status
            // transitions: they always apply and they reset the fence, so a service
            // that re-registers after a restart is not blocked by the generation its
            // previous incarnation reached.
            // A service registers from its own ApplicationReadyEvent, i.e. registration
            // *is* the service asserting it can serve traffic. So we route on
            // registration and let subsequent status transitions tear the route down.
            case SERVICE_REGISTERED -> {
                lastGeneration.put(event.getServiceName(), event.getGeneration());
                addRoute(event);
            }
            case SERVICE_DEREGISTERED -> {
                lastGeneration.remove(event.getServiceName());
                removeRoute(event.getServiceName());
            }
            case STATUS_CHANGED -> {
                if (isStale(event)) return;
                lastGeneration.put(event.getServiceName(), event.getGeneration());
                applyStatus(event);
            }
        }
    }

    private boolean isStale(ServiceEvent event) {
        Long seen = lastGeneration.get(event.getServiceName());
        if (seen != null && event.getGeneration() < seen) {
            System.out.println("[Gateway] Dropping stale event for " + event.getServiceName()
                    + " (gen=" + event.getGeneration() + " < seen=" + seen + ")");
            return true;
        }
        return false;
    }

    /** Route exists if and only if the service is healthy. */
    private void applyStatus(ServiceEvent event) {
        if (ROUTABLE_STATUS.equals(event.getStatus())) {
            addRoute(event);
        } else {
            removeRoute(event.getServiceName());
        }
    }

    private void addRoute(ServiceEvent event) {
        if (event.getUrl() == null) {
            System.err.println("[Gateway] Cannot route " + event.getServiceName() + ": no url on event");
            return;
        }

        RouteDefinition route = new RouteDefinition();
        route.setId(event.getServiceName());
        route.setUri(URI.create(event.getUrl()));

        PredicateDefinition pathPredicate = new PredicateDefinition();
        pathPredicate.setName("Path");
        pathPredicate.addArg("pattern", "/route/" + event.getServiceName() + "/**");
        route.setPredicates(List.of(pathPredicate));

        // Strip /route/{serviceName} prefix before forwarding — /route/service-b/greet → /greet
        FilterDefinition stripPrefix = new FilterDefinition();
        stripPrefix.setName("StripPrefix");
        stripPrefix.addArg("parts", "2");
        route.setFilters(List.of(stripPrefix));

        routeDefinitionWriter.save(Mono.just(route))
                .doOnSuccess(v -> {
                    eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                    System.out.println("[Gateway] Route added: " + event.getServiceName() + " → " + event.getUrl());
                })
                .subscribe();

        routeRegistry.add(new RouteInfo(
                event.getServiceName(), event.getUrl(), event.getOpenapiUrl(), event.getStatus()));
    }

    private void removeRoute(String serviceName) {
        routeDefinitionWriter.delete(Mono.just(serviceName))
                .doOnSuccess(v -> {
                    eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                    System.out.println("[Gateway] Route removed: " + serviceName);
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();

        routeRegistry.remove(serviceName);
    }
}
