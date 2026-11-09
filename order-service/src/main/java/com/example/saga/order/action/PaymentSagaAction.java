package com.example.saga.order.action;

import com.example.saga.api.OperationResult;
import com.example.saga.api.PaymentCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 状态机中的支付远程动作。pay 与 refund 是一对业务正向/补偿操作。 */
@Component("paymentAction")
public class PaymentSagaAction {
    private static final Logger log = LoggerFactory.getLogger(PaymentSagaAction.class);
    private final RestClient restClient;

    public PaymentSagaAction(@Value("${app.remote.payment-url}") String paymentUrl) {
        this.restClient = RestClient.builder().baseUrl(paymentUrl).build();
    }

    public boolean pay(String orderNo, Long amountCents, String failAt) {
        log.info("[SAGA-ACTION][PAYMENT][FORWARD][CALL] orderNo={}, amountCents={}", orderNo, amountCents);
        OperationResult result = restClient.post()
                .uri("/internal/payment/pay")
                .body(new PaymentCommand(orderNo, amountCents, failAt))
                .retrieve()
                .body(OperationResult.class);
        if (result == null || !result.success()) {
            throw new IllegalStateException("支付服务返回失败：" + result);
        }
        log.info("[SAGA-ACTION][PAYMENT][FORWARD][DONE] orderNo={}, state={}", orderNo, result.state());
        return true;
    }

    public boolean refund(String orderNo, Long amountCents) {
        log.warn("[SAGA-ACTION][PAYMENT][COMPENSATE][CALL] orderNo={}, amountCents={}", orderNo, amountCents);
        OperationResult result = restClient.post()
                .uri("/internal/payment/refund")
                .body(new PaymentCommand(orderNo, amountCents, "NONE"))
                .retrieve()
                .body(OperationResult.class);
        if (result == null || !result.success()) {
            throw new IllegalStateException("支付补偿返回失败：" + result);
        }
        log.warn("[SAGA-ACTION][PAYMENT][COMPENSATE][DONE] orderNo={}, state={}", orderNo, result.state());
        return true;
    }
}
