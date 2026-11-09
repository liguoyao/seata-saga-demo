package com.example.saga.api;

public record OrderView(
        String orderNo,
        String productId,
        Integer quantity,
        Long amountCents,
        String status
) {
}
