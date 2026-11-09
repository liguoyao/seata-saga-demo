package com.example.saga.api;

/** 库存正向扣减和逆向恢复共用的业务参数。 */
public record InventoryCommand(
        String orderNo,
        String productId,
        Integer quantity,
        String failAt
) {
}
