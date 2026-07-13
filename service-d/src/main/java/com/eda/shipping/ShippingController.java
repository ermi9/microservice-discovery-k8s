package com.eda.shipping;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class ShippingController {

    private final Map<String, Map<String, Object>> shipments = new LinkedHashMap<>();
    private int nextId = 1;

    @PostMapping("/shipments")
    public ResponseEntity<?> createShipment(@RequestBody Map<String, Object> request) {
        String orderId = (String) request.get("orderId");
        String address = (String) request.get("address");
        if (orderId == null || address == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderId and address are required"));
        }

        String shipmentId = "SHP-" + (nextId++);
        String tracking = "TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("shipmentId", shipmentId);
        shipment.put("orderId", orderId);
        shipment.put("address", address);
        shipment.put("trackingNumber", tracking);
        shipment.put("status", "dispatched");
        shipment.put("dispatchedAt", LocalDateTime.now().toString());
        shipments.put(shipmentId, shipment);
        return ResponseEntity.ok(shipment);
    }

    @GetMapping("/shipments")
    public Collection<Map<String, Object>> listShipments() {
        return shipments.values();
    }

    @GetMapping("/shipments/{id}")
    public ResponseEntity<?> getShipment(@PathVariable String id) {
        Map<String, Object> shipment = shipments.get(id);
        if (shipment == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(shipment);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
