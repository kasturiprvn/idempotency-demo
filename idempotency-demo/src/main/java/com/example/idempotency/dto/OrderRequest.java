package com.example.idempotency.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class OrderRequest {

    @NotBlank
    private String customerId;

    @NotBlank
    private String productId;

    @Min(1)
    private int quantity;

    public OrderRequest() {
    }

    public OrderRequest(String customerId, String productId, int quantity) {
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
