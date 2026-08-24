package com.example.idempotency.controller;

import com.example.idempotency.dto.OrderRequest;
import com.example.idempotency.dto.OrderResponse;
import com.example.idempotency.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Create an order. Protected by IdempotencyFilter when called with an
     * "Idempotency-Key" header - retries with the same key + body are safe and
     * will replay the original response instead of creating a duplicate order.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Exposed purely for tests to verify how many times the handler actually ran. */
    @GetMapping("/_debug/call-count")
    public ResponseEntity<Integer> callCount() {
        return ResponseEntity.ok(orderService.getCallCount());
    }
}
