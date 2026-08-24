package com.example.idempotency.service;

import com.example.idempotency.dto.OrderRequest;
import com.example.idempotency.dto.OrderResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in "business logic". The call counter exists purely so tests can assert the handler
 * was only really executed once, even when the same Idempotency-Key is used for several
 * duplicate HTTP requests.
 */
@Service
public class OrderService {

    private final AtomicInteger callCount = new AtomicInteger(0);

    public OrderResponse createOrder(OrderRequest request) {
        callCount.incrementAndGet();
        return new OrderResponse(
                UUID.randomUUID().toString(),
                request.getCustomerId(),
                request.getProductId(),
                request.getQuantity(),
                "CREATED",
                Instant.now()
        );
    }

    public int getCallCount() {
        return callCount.get();
    }
}
