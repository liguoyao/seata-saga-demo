package com.example.saga.order.action;

import com.example.saga.api.InventoryCommand;
import com.example.saga.api.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 状态机中的库存远程动作：正向扣减与逆向恢复必须一一对应。 */
@Component("inventoryAction")
public class InventorySagaAction {
    private static final Logger log = LoggerFactory.getLogger(InventorySagaAction.class);
    private final RestClient restClient;

    public InventorySagaAction(@Value("${app.remote.inventory-url}") String inventoryUrl) {
        this.restClient = RestClient.builder().baseUrl(inventoryUrl).build();
    }

    /** Saga 正向节点调用的方法。 */
    public boolean deduct(String orderNo, String productId, Integer quantity, String failAt) {
        log.info("[SAGA-ACTION][INVENTORY][FORWARD][CALL] orderNo={}, productId={}, quantity={}",
                orderNo, productId, quantity);
        OperationResult result = restClient.post()
                .uri("/internal/inventory/deduct")
                .body(new InventoryCommand(orderNo, productId, quantity, failAt))
                .retrieve()
                .body(OperationResult.class);
        if (result == null || !result.success()) {
            throw new IllegalStateException("库存服务返回失败：" + result);
        }
        log.info("[SAGA-ACTION][INVENTORY][FORWARD][DONE] orderNo={}, state={}", orderNo, result.state());
        return true;
    }

    /** Saga 补偿节点调用的方法。补偿必须可重入、幂等。 */
    public boolean restore(String orderNo, String productId, Integer quantity) {
        log.warn("[SAGA-ACTION][INVENTORY][COMPENSATE][CALL] orderNo={}, productId={}, quantity={}",
                orderNo, productId, quantity);
        OperationResult result = restClient.post()
                .uri("/internal/inventory/restore")
                .body(new InventoryCommand(orderNo, productId, quantity, "NONE"))
                .retrieve()
                .body(OperationResult.class);
        if (result == null || !result.success()) {
            throw new IllegalStateException("库存补偿返回失败：" + result);
        }
        log.warn("[SAGA-ACTION][INVENTORY][COMPENSATE][DONE] orderNo={}, state={}", orderNo, result.state());
        return true;
    }
}
