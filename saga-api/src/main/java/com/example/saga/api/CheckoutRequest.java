package com.example.saga.api;

/**
 * 下单请求。failAt 仅用于演示补偿，可选值：NONE、INVENTORY、PAYMENT、ORDER_UPDATE。
 * amountCents 是优惠前金额，允许为 0；couponCode 可选。COUPON 模拟核销后失败。
 */
public record CheckoutRequest(
        String orderNo,
        String productId,
        Integer quantity,
        Long amountCents,
        String failAt,
        String couponCode
) {
}
