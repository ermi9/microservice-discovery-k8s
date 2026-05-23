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

@Component
public class ServiceEventConsumer {

    @Autowired
    private RouteDefinitionWriter routeDefinitionWriter;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private RouteRegistry routeRegistry;

    @KafkaListener(topics = "service-events", groupId = "api-gateway-group")
    public void onServiceEvent(ServiceEvent event) {
        System.out.println("[Gateway] Received event: " + event.getType() + " for " + event.getServiceName());

        switch (event.getType()) {
            case SERVICE_REGISTERED -> addRoute(event);
            case SERVICE_DEREGISTERED -> removeRoute(event.getServiceName());
            case STATUS_CHANGED -> {
                //  re-add only if still healthy
                removeRoute(event.getServiceName());
                if (!"unavailable".equals(event.getStatus()) && !"unhealthy".equals(event.getStatus())) {
                    addRoute(event);
                }
            }
        }
    }

    private void addRoute(ServiceEvent event) {
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
