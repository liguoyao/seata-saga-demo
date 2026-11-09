package com.example.saga.api;

/** 支付正向扣款和逆向退款共用的业务参数。 */
public record PaymentCommand(
        String orderNo,
        Long amountCents,
        String failAt
) {
}
