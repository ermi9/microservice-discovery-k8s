package com.eda.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class OrderController {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    private final Map<String, Map<String, Object>> orders = new LinkedHashMap<>();
    private int nextId = 1;

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> request) {
        String productId = (String) request.get("productId");
        Object qtyRaw    = request.get("quantity");
        String customer  = (String) request.getOrDefault("customer", "unknown");

        if (productId == null || qtyRaw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "productId and quantity are required"));
        }

        int quantity = ((Number) qtyRaw).intValue();
        if (quantity <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "quantity must be greater than zero"));
        }

        // Ask the inventory service (via gateway) to reserve the stock.
        // The reserve endpoint does the check and deduction atomically — service-a
        // never reads the stock value directly, so there is no race condition.
        String reserveUrl = gatewayUrl + "/route/service-b/products/" + productId
                            + "/reserve?quantity=" + quantity;
        Map<?, ?> product;
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(reserveUrl, null, Map.class);
            product = response.getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ResponseEntity.badRequest().body(Map.of("error", "product not found: " + productId));
            }
            // 400 means insufficient stock — forward the error body from service-b
            return ResponseEntity.badRequest().body(e.getResponseBodyAs(Map.class));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "inventory service unavailable: " + e.getMessage()));
        }

        if (product == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "product not found: " + productId));
        }

        String orderId = "ORD-" + (nextId++);
        double unitPrice = ((Number) product.get("price")).doubleValue();

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", orderId);
        order.put("customer", customer);
        order.put("productId", productId);
        order.put("productName", product.get("name"));
        order.put("quantity", quantity);
        order.put("unitPrice", unitPrice);
        order.put("total", unitPrice * quantity);
        order.put("status", "confirmed");
        order.put("createdAt", LocalDateTime.now().toString());

        orders.put(orderId, order);
        return ResponseEntity.ok(order);
    }

    @GetMapping("/orders")
    public Collection<Map<String, Object>> listOrders() {
        return orders.values();
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<?> getOrder(@PathVariable String id) {
        Map<String, Object> order = orders.get(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
