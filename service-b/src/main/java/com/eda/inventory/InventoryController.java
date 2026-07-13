package com.eda.inventory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class InventoryController {

    private static final Map<String, Map<String, Object>> PRODUCTS = new LinkedHashMap<>();

    static {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("id", "P001");
        p1.put("name", "Wireless Headphones");
        p1.put("price", 79.99);
        p1.put("stock", 42);
        PRODUCTS.put("P001", p1);

        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("id", "P002");
        p2.put("name", "USB-C Hub");
        p2.put("price", 34.99);
        p2.put("stock", 15);
        PRODUCTS.put("P002", p2);

        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("id", "P003");
        p3.put("name", "Mechanical Keyboard");
        p3.put("price", 129.99);
        p3.put("stock", 0);
        PRODUCTS.put("P003", p3);
    }

    @GetMapping("/products")
    public Collection<Map<String, Object>> listProducts() {
        return PRODUCTS.values();
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<?> getProduct(@PathVariable String id) {
        Map<String, Object> product = PRODUCTS.get(id);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product);
    }

    // Atomically checks stock and deducts it if available.
    // Doing both steps here in service-b prevents a race where two callers
    // both read "42 in stock", both pass the check, and both place an order.
    @PostMapping("/products/{id}/reserve")
    public ResponseEntity<?> reserveStock(@PathVariable String id, @RequestParam int quantity) {
        synchronized (PRODUCTS) {
            Map<String, Object> product = PRODUCTS.get(id);
            if (product == null) {
                return ResponseEntity.notFound().build();
            }
            int stock = ((Number) product.get("stock")).intValue();
            if (stock < quantity) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "insufficient stock",
                    "requested", quantity,
                    "available", stock
                ));
            }
            product.put("stock", stock - quantity);
            return ResponseEntity.ok(product);
        }
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
