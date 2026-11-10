package com.example.saga.order.service;

import com.example.saga.api.CheckoutRequest;
import com.example.saga.api.CheckoutResult;
import org.apache.seata.saga.engine.StateMachineEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Saga 发起入口。
 * 这里不使用 @GlobalTransactional：状态机引擎负责创建、推进和结束 Saga 全局事务。
 */
@Service
public class OrderSagaService {
    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);
    private static final String STATE_MACHINE_NAME = "placeOrderSaga";
    private static final String TENANT_ID = "000001";

    private final StateMachineEngine stateMachineEngine;
    private final JdbcTemplate jdbcTemplate;

    public OrderSagaService(StateMachineEngine stateMachineEngine, JdbcTemplate jdbcTemplate) {
        this.stateMachineEngine = stateMachineEngine;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CheckoutResult checkout(CheckoutRequest request) {
        validate(request);
        String failAt = normalizeFailAt(request.failAt());

        // startParams 就是状态机上下文。JSON 中的 $.[orderNo] 等表达式会从这里取值。
        Map<String, Object> startParams = new HashMap<>();
        startParams.put("orderNo", request.orderNo());
        startParams.put("productId", request.productId());
        startParams.put("quantity", request.quantity());
        startParams.put("amountCents", request.amountCents());
        startParams.put("failAt", failAt);
        startParams.put("couponCode", request.couponCode() == null ? "" : request.couponCode().trim());
        startParams.put("payableCents", request.amountCents());

        log.info("============================================================");
        log.info("[SAGA][START] stateMachine={}, businessKey={}, params={}",
                STATE_MACHINE_NAME, request.orderNo(), startParams);
        log.info("[SAGA][FLOW] CreateOrder -> DeductInventory -> CouponSubMachine -> PaymentChoice -> 支付或零元完成");
        log.info("[SAGA][COMPENSATION] 失败时逆序执行退款（如有）、优惠券子状态机退券、恢复库存、取消订单");

        String instanceId = null;
        String engineError = null;
        try {
            // businessKey 使用 orderNo，便于排查、幂等约束及按业务单号检索 Saga 实例。
            var instance = stateMachineEngine.startWithBusinessKey(
                    STATE_MACHINE_NAME, TENANT_ID, request.orderNo(), startParams);
            instanceId = instance.getId();
            log.info("[SAGA][ENGINE_RETURN] orderNo={}, instanceId={}, engineStatus={}",
                    request.orderNo(), instanceId, instance.getStatus());
        } catch (Exception ex) {
            // 某些 Fail/引擎异常会以异常形式返回。不要据此推断没有补偿，下面仍查询持久化状态。
            engineError = ex.getMessage();
            log.error("[SAGA][ENGINE_EXCEPTION] orderNo={}，继续读取 Saga 持久化日志确认最终状态",
                    request.orderNo(), ex);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id AS instance_id,
                       status AS saga_status,
                       compensation_status AS compensation_status
                  FROM seata_state_machine_inst
                 WHERE business_key=? AND tenant_id=? AND parent_id IS NULL
                 ORDER BY gmt_started DESC
                 LIMIT 1
                """, request.orderNo(), TENANT_ID);

        String status = "UNKNOWN";
        String compensationStatus = "UNKNOWN";
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            if (instanceId == null) {
                instanceId = String.valueOf(row.get("instance_id"));
            }
            status = stringValue(row.get("saga_status"));
            compensationStatus = stringValue(row.get("compensation_status"));
        }

        boolean success = "SU".equalsIgnoreCase(status);
        String message;
        if (success) {
            message = "Saga 执行成功，订单已完成（实付为零时跳过支付）";
        } else if ("SU".equalsIgnoreCase(compensationStatus)) {
            message = "正向流程失败，补偿流程已成功完成，业务数据已回滚到可接受状态";
        } else if (engineError != null) {
            message = "Saga 引擎执行异常，需结合状态机日志检查；" + engineError;
        } else {
            message = "Saga 未成功结束，请检查状态机实例和补偿状态";
        }

        log.info("[SAGA][END] orderNo={}, instanceId={}, status={}, compensationStatus={}, success={}, message={}",
                request.orderNo(), instanceId, status, compensationStatus, success, message);
        log.info("============================================================");
        return new CheckoutResult(request.orderNo(), instanceId, status, compensationStatus, success, message);
    }

    private void validate(CheckoutRequest request) {
        if (request == null) throw new IllegalArgumentException("请求体不能为空");
        if (request.orderNo() == null || request.orderNo().isBlank() || request.orderNo().length() > 48) {
            throw new IllegalArgumentException("orderNo 不能为空且长度不能超过 48");
        }
        if (request.productId() == null || request.productId().isBlank()) {
            throw new IllegalArgumentException("productId 不能为空");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        if (request.amountCents() == null || request.amountCents() < 0) {
            throw new IllegalArgumentException("amountCents 必须大于或等于 0");
        }
        if (request.couponCode() != null && request.couponCode().trim().length() > 48) {
            throw new IllegalArgumentException("couponCode 长度不能超过 48");
        }
    }

    private String normalizeFailAt(String failAt) {
        if (failAt == null || failAt.isBlank()) return "NONE";
        String normalized = failAt.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "INVENTORY", "PAYMENT", "ORDER_UPDATE", "COUPON").contains(normalized)) {
            throw new IllegalArgumentException("failAt 仅支持 NONE、INVENTORY、PAYMENT、ORDER_UPDATE、COUPON");
        }
        return normalized;
    }

    private String stringValue(Object value) {
        return value == null ? "NONE" : String.valueOf(value);
    }
}
