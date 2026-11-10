package com.example.saga.order.action;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 固定面额、单次使用优惠券；流水负责幂等、空补偿和防悬挂。 */
@Component("couponAction")
public class CouponSagaAction {
    private static final Logger log = LoggerFactory.getLogger(CouponSagaAction.class);
    private final JdbcTemplate jdbc;

    public CouponSagaAction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean validate(String couponCode) {
        var rows = jdbc.queryForList("SELECT discount_cents FROM t_coupon WHERE coupon_code=?", couponCode);
        if (rows.isEmpty()) throw new IllegalArgumentException("优惠券不存在：" + couponCode);
        return true;
    }

    @Transactional
    public Long redeem(String orderNo, String couponCode, Long amountCents) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO t_coupon_operation(order_no, coupon_code, original_cents, payable_cents, state)
                VALUES (?, ?, ?, ?, 'REDEEMING')
                """, orderNo, couponCode, amountCents, amountCents);
        Map<String, Object> op = operation(orderNo);
        if (!couponCode.equals(op.get("coupon_code"))
                || amountCents.longValue() != ((Number) op.get("original_cents")).longValue()) {
            throw new IllegalArgumentException("同一订单的优惠券或金额不一致");
        }
        if (inserted == 0) {
            if ("USED".equals(op.get("state"))) return ((Number) op.get("payable_cents")).longValue();
            throw new IllegalStateException("已退券或状态异常，拒绝迟到核销：" + orderNo);
        }
        var coupons = jdbc.queryForList("SELECT * FROM t_coupon WHERE coupon_code=? FOR UPDATE", couponCode);
        if (coupons.isEmpty() || coupons.get(0).get("used_order_no") != null) {
            throw new IllegalStateException("优惠券不存在或已被占用：" + couponCode);
        }
        long payable = Math.max(0, amountCents - ((Number) coupons.get(0).get("discount_cents")).longValue());
        jdbc.update("UPDATE t_coupon SET used_order_no=? WHERE coupon_code=?", orderNo, couponCode);
        jdbc.update("UPDATE t_coupon_operation SET payable_cents=?, state='USED' WHERE order_no=?", payable, orderNo);
        log.info("[COUPON][REDEEM] orderNo={}, couponCode={}, payableCents={}", orderNo, couponCode, payable);
        return payable;
    }

    /** 在核销本地事务提交之后模拟异常，演示子流程内部补偿。 */
    public boolean confirm(String orderNo, String failAt) {
        if ("COUPON".equalsIgnoreCase(failAt)) throw new IllegalStateException("模拟优惠券核销后失败：" + orderNo);
        return true;
    }

    @Transactional
    public boolean release(String orderNo, String couponCode, Long amountCents) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO t_coupon_operation(order_no, coupon_code, original_cents, payable_cents, state)
                VALUES (?, ?, ?, ?, 'RELEASED')
                """, orderNo, couponCode, amountCents, amountCents);
        Map<String, Object> op = operation(orderNo);
        if (inserted == 1 || "RELEASED".equals(op.get("state"))) return true;
        if (!"USED".equals(op.get("state"))) throw new IllegalStateException("优惠券补偿状态异常：" + orderNo);
        // 使用持久化流水中的券号，避免根据重试参数误退其他券。
        jdbc.update("UPDATE t_coupon SET used_order_no=NULL WHERE coupon_code=? AND used_order_no=?",
                op.get("coupon_code"), orderNo);
        jdbc.update("UPDATE t_coupon_operation SET state='RELEASED' WHERE order_no=?", orderNo);
        log.warn("[COUPON][RELEASE] orderNo={}, couponCode={}", orderNo, op.get("coupon_code"));
        return true;
    }

    private Map<String, Object> operation(String orderNo) {
        return jdbc.queryForMap("SELECT * FROM t_coupon_operation WHERE order_no=? FOR UPDATE", orderNo);
    }
}
