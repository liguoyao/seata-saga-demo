package com.example.saga.api;

/**
 * 下单请求。failAt 仅用于演示补偿，可选值：NONE、INVENTORY、PAYMENT、ORDER_UPDATE。
 * amountCents 使用“分”作为单位，避免示例代码中的浮点金额误差。
 */
public record CheckoutRequest(
        String orderNo,
        String productId,
        Integer quantity,
        Long amountCents,
        String failAt
) {
}
