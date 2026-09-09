package com.eda.gateway.controller;

import com.eda.gateway.model.RouteInfo;
import com.eda.gateway.registry.RouteRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.function.Function;
import java.util.function.Predicate;

@RestController
public class GatewayController {

    @Autowired
    private RouteRegistry routeRegistry;

    private final WebClient webClient = WebClient.builder().build();

    /**
     * Lists every service the gateway currently routes to, along with the URL
     * where its live OpenAPI spec can be fetched.
     *
     * <p>Note on terminology: this gateway is <em>schema-transparent</em>, not
     * schema-aware. It knows where each service's spec lives and proxies it through
     * one stable URL ({@link #getOpenApiSpec}), but it does not parse or interpret
     * specs and makes no routing decision based on their contents. The structured
     * capability catalog — "does service X support operation Y?" — is built and
     * served by the discovery-service at {@code GET /services/{name}/capabilities}.
     */
    @GetMapping("/services")
    public Collection<RouteInfo> listServices() {
        return routeRegistry.getAll();
    }

    /**
     * Fetches and returns the OpenAPI spec of a registered service by proxying
     * directly to that service's /v3/api-docs endpoint. Clients (Swagger UI,
     * Postman, code generators) can therefore discover any microservice's full
     * contract through a single, stable gateway URL.
     *
     * <p>This is a byte-for-byte pass-through: the spec is streamed as an opaque
     * String and is never deserialized or inspected here.
     */
    @GetMapping("/openapi/{serviceName}")
    public Mono<String> getOpenApiSpec(@PathVariable String serviceName) {
        RouteInfo route = routeRegistry.get(serviceName);
        if (route == null || route.getOpenapiUrl() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No OpenAPI spec registered for: " + serviceName));
        }
        return webClient.get()
                .uri(route.getOpenapiUrl())
                .retrieve()
                .onStatus(new Predicate<HttpStatusCode>() {
                    @Override
                    public boolean test(HttpStatusCode status) {
                        return status.is4xxClientError() || status.is5xxServerError();
                    }
                }, new Function<ClientResponse, Mono<? extends Throwable>>() {
                    @Override
                    public Mono<? extends Throwable> apply(ClientResponse resp) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY, "Upstream returned " + resp.statusCode()));
                    }
                })
                .bodyToMono(String.class);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
