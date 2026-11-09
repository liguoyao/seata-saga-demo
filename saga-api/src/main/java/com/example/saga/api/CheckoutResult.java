package com.example.saga.api;

/** 下单 Saga 执行结果。status/compensationStatus 对应 Seata 状态机日志中的状态字段。 */
public record CheckoutResult(
        String orderNo,
        String stateMachineInstanceId,
        String status,
        String compensationStatus,
        boolean success,
        String message
) {
}
