package com.eda.discovery.service;

import com.eda.discovery.model.Service;
import com.eda.discovery.repository.ServiceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses each registered service's OpenAPI document into a queryable operation catalog.
 *
 * <p>The rest of the platform only records <em>where</em> a service's contract lives — an
 * {@code openapiUrl} that the gateway proxies as opaque bytes. This is the one place that
 * reads it, so the registry can answer "does {@code service-b} support
 * {@code POST /products/{id}/reserve}?" without the caller fetching and parsing the
 * document itself.
 *
 * <p>Deliberately parsed with Jackson against the {@code paths} object rather than pulling
 * in a full OpenAPI model library: the catalog only needs operation identity
 * ({@code METHOD /path} and any declared {@code operationId}), and a heavyweight parser
 * would add a large dependency and a spec-version compatibility surface for no gain.
 *
 * <p>Fetching happens out of band, never during registration: a service registering
 * should not block on its own spec endpoint, and a slow or broken spec must not fail a
 * registration that is otherwise valid.
 */
@Component
public class CapabilityCatalogService {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private LeaderElectionService leaderElectionService;

    private final ObjectMapper mapper = new ObjectMapper();
    private RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Fills in capabilities for services that do not have them yet. Leader-gated so three
     * replicas do not all hammer every service's spec endpoint; the result lands in Redis
     * and is therefore visible to all of them.
     */
    @Scheduled(fixedDelayString = "${capabilities.refresh-interval-ms:60000}")
    public void refreshMissingCatalogs() {
        if (!leaderElectionService.isLeader()) return;

        for (Service service : serviceRegistry.getAllServices()) {
            if (service.getCapabilities() == null || service.getCapabilities().isEmpty()) {
                refresh(service.getName());
            }
        }
    }

    /**
     * Fetches and re-parses one service's spec now.
     *
     * @return the parsed operations, or an empty set if the service is unknown, has no
     *         spec URL, or its spec could not be fetched or parsed.
     */
    public Set<String> refresh(String serviceName) {
        Service service = serviceRepository.findById(serviceName).orElse(null);
        if (service == null || service.getOpenapiUrl() == null) {
            return Set.of();
        }

        Set<String> operations = fetchOperations(service.getOpenapiUrl());
        if (operations.isEmpty()) {
            return Set.of();
        }

        service.setCapabilities(operations);
        serviceRepository.save(service);
        System.out.println("[Capabilities] " + serviceName + " → " + operations.size() + " operations");
        return operations;
    }

    /** Answers the planner's question directly: can this service do this? */
    public boolean supports(String serviceName, String operation) {
        Service service = serviceRepository.findById(serviceName).orElse(null);
        return service != null && service.supports(operation);
    }

    private Set<String> fetchOperations(String openapiUrl) {
        Set<String> operations = new LinkedHashSet<>();
        try {
            String body = restTemplate.getForObject(openapiUrl, String.class);
            if (body == null || body.isBlank()) return operations;

            JsonNode paths = mapper.readTree(body).path("paths");
            if (!paths.isObject()) return operations;

            Iterator<String> pathNames = paths.fieldNames();
            while (pathNames.hasNext()) {
                String path = pathNames.next();
                JsonNode pathItem = paths.get(path);

                Iterator<String> methods = pathItem.fieldNames();
                while (methods.hasNext()) {
                    String method = methods.next();
                    if (!HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))) continue;

                    operations.add(method.toUpperCase(Locale.ROOT) + " " + path);

                    JsonNode operationId = pathItem.get(method).get("operationId");
                    if (operationId != null && operationId.isTextual()) {
                        operations.add(operationId.asText());
                    }
                }
            }
        } catch (Exception e) {
            // A spec we cannot read leaves the catalog untouched rather than wiping it:
            // a transient fetch failure must not make a service look capability-less.
            System.err.println("[Capabilities] Failed to parse spec at " + openapiUrl + ": " + e.getMessage());
        }
        return operations;
    }
}
