package com.example.saga.payment.service;

import com.example.saga.api.OperationResult;
import com.example.saga.api.PaymentCommand;
import com.example.saga.api.PaymentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 支付服务自己的本地事务。Saga 不会替我们自动退款，退款逻辑必须由业务方实现。 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final JdbcTemplate jdbcTemplate;

    public PaymentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 正向动作：支付。 */
    @Transactional
    public OperationResult pay(PaymentCommand command) {
        String orderNo = command.orderNo();
        long amountCents = command.amountCents() == null ? 0L : command.amountCents();
        log.info("[PAYMENT][FORWARD][BEGIN] orderNo={}, amountCents={}, failAt={}",
                orderNo, amountCents, command.failAt());

        if ("PAYMENT".equalsIgnoreCase(command.failAt())) {
            log.error("[PAYMENT][FORWARD][SIMULATED_FAILURE] orderNo={}，模拟支付渠道失败", orderNo);
            throw new IllegalStateException("模拟支付失败：支付渠道返回失败");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents 必须大于 0");
        }

        // 先插入 PAYING 记录获取业务唯一键。事务内后续失败时该记录自动回滚。
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO t_payment_operation(order_no, amount_cents, state)
                VALUES (?, ?, 'PAYING')
                """, orderNo, amountCents);
        if (inserted == 0) {
            String state = findState(orderNo);
            if ("PAID".equals(state)) {
                log.warn("[PAYMENT][IDEMPOTENT] orderNo={} 已支付，跳过重复扣款", orderNo);
                return new OperationResult(true, state, "重复请求，订单已支付");
            }
            throw new IllegalStateException("支付操作已退款或状态异常，拒绝迟到支付，orderNo=" + orderNo + ", state=" + state);
        }

        jdbcTemplate.update("UPDATE t_payment_operation SET state='PAID' WHERE order_no=?", orderNo);
        log.info("[PAYMENT][FORWARD][COMMIT] orderNo={}, amountCents={}，支付成功", orderNo, amountCents);
        return new OperationResult(true, "PAID", "支付成功");
    }

    /** 反向补偿：退款。空补偿和重复补偿均视为成功。 */
    @Transactional
    public OperationResult refund(PaymentCommand command) {
        String orderNo = command.orderNo();
        long amountCents = command.amountCents() == null ? 0L : command.amountCents();
        log.warn("[PAYMENT][COMPENSATE][BEGIN] orderNo={}, amountCents={}", orderNo, amountCents);

        // 如果 pay 从未成功落库，插入 REFUNDED 墓碑，避免之后迟到的 pay 真正扣款（防悬挂）。
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO t_payment_operation(order_no, amount_cents, state)
                VALUES (?, ?, 'REFUNDED')
                """, orderNo, amountCents);
        if (inserted == 1) {
            log.warn("[PAYMENT][COMPENSATE][EMPTY] orderNo={} 没有找到支付流水，登记空补偿标记", orderNo);
            return new OperationResult(true, "REFUNDED", "空退款补偿成功，已登记防悬挂标记");
        }

        String state = findState(orderNo);
        if ("REFUNDED".equals(state)) {
            log.warn("[PAYMENT][COMPENSATE][IDEMPOTENT] orderNo={} 已退款，跳过重复补偿", orderNo);
            return new OperationResult(true, state, "重复补偿，支付已退款");
        }
        if (!"PAID".equals(state)) {
            throw new IllegalStateException("支付补偿遇到非预期状态，orderNo=" + orderNo + ", state=" + state);
        }

        jdbcTemplate.update("UPDATE t_payment_operation SET state='REFUNDED' WHERE order_no=?", orderNo);
        log.warn("[PAYMENT][COMPENSATE][COMMIT] orderNo={}，退款成功", orderNo);
        return new OperationResult(true, "REFUNDED", "退款成功");
    }

    public PaymentView getPayment(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_no, amount_cents, state FROM t_payment_operation WHERE order_no=?", orderNo);
        if (rows.isEmpty()) {
            return new PaymentView(orderNo, null, "NONE");
        }
        Map<String, Object> row = rows.get(0);
        Object amount = row.get("amount_cents");
        return new PaymentView(orderNo, amount == null ? null : ((Number) amount).longValue(),
                String.valueOf(row.get("state")));
    }

    private String findState(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state FROM t_payment_operation WHERE order_no=?", orderNo);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("state"));
    }
}
