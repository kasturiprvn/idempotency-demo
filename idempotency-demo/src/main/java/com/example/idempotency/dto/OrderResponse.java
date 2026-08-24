package com.example.idempotency.dto;

import java.time.Instant;

public class OrderResponse {

    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private String status;
    private Instant createdAt;

    public OrderResponse() {
    }

    public OrderResponse(String orderId, String customerId, String productId, int quantity,
                          String status, Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
