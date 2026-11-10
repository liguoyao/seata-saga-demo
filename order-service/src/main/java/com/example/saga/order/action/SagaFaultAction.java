package com.example.saga.order.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/** 仅模拟故障；不访问现有订单、库存或支付数据。 */
@Component("sagaFaultAction")
public class SagaFaultAction {
    private static final Logger log = LoggerFactory.getLogger(SagaFaultAction.class);

    public boolean first(String key) {
        log.info("[FAULT][FORWARD-FIRST] key={}", key);
        return true;
    }

    public boolean second(String key, String scenario) throws Exception {
        log.info("[FAULT][FORWARD-SECOND] key={}, scenario={}", key, scenario);
        switch (scenario) {
            case "FORWARD_EXCEPTION", "COMPENSATION_EXCEPTION" ->
                throw new IllegalStateException("模拟正向异常: " + key);
            case "READ_TIMEOUT" -> throw new SocketTimeoutException("模拟网络读取超时: " + key);
            case "CONNECT_FAILURE" -> throw new ConnectException("模拟网络连接失败: " + key);
            default -> {
                return true;
            }
        }
    }

    public boolean undoFirst(String key, String scenario) {
        log.warn("[FAULT][COMPENSATE-FIRST] key={}, scenario={}", key, scenario);
        if ("COMPENSATION_EXCEPTION".equals(scenario)) {
            throw new IllegalStateException("模拟补偿异常: " + key);
        }
        return true;
    }

    public boolean undoSecond(String key) {
        log.warn("[FAULT][COMPENSATE-SECOND] key={}", key);
        return true;
    }
}
