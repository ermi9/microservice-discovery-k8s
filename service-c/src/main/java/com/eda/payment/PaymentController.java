package com.eda.payment;

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

@RestController
public class PaymentController {

    private final Map<String, Map<String, Object>> payments = new LinkedHashMap<>();
    private int nextId = 1;

    @PostMapping("/payments")
    public ResponseEntity<?> charge(@RequestBody Map<String, Object> request) {
        String orderId = (String) request.get("orderId");
        Object amountRaw = request.get("amount");
        if (orderId == null || amountRaw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderId and amount are required"));
        }
        double amount = ((Number) amountRaw).doubleValue();
        if (amount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount must be greater than zero"));
        }

        String paymentId = "PAY-" + (nextId++);
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("paymentId", paymentId);
        payment.put("orderId", orderId);
        payment.put("amount", amount);
        payment.put("status", "captured");
        payment.put("capturedAt", LocalDateTime.now().toString());
        payments.put(paymentId, payment);
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/payments")
    public Collection<Map<String, Object>> listPayments() {
        return payments.values();
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<?> getPayment(@PathVariable String id) {
        Map<String, Object> payment = payments.get(id);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
