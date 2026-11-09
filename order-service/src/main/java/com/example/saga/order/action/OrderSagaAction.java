package com.example.saga.order.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 状态机中的订单本地动作。
 * ServiceName=orderAction 对应 Spring Bean 名称；ServiceMethod 对应这里的方法名。
 */
@Component("orderAction")
public class OrderSagaAction {
    private static final Logger log = LoggerFactory.getLogger(OrderSagaAction.class);
    private final JdbcTemplate jdbcTemplate;

    public OrderSagaAction(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 正向动作：创建订单。此方法本身是订单库内的一个本地事务。 */
    @Transactional
    public boolean createOrder(String orderNo, String productId, Integer quantity, Long amountCents) {
        log.info("[ORDER][FORWARD][BEGIN] orderNo={}, productId={}, quantity={}, amountCents={}",
                orderNo, productId, quantity, amountCents);

        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO t_order(order_no, product_id, quantity, amount_cents, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, orderNo, productId, quantity, amountCents);

        if (inserted == 0) {
            String currentStatus = findStatus(orderNo);
            if ("CANCELLED".equals(currentStatus)) {
                // 补偿先到、正向请求后到：拒绝创建，防止悬挂。
                throw new IllegalStateException("订单已被补偿取消，拒绝迟到的创建请求，orderNo=" + orderNo);
            }
            log.warn("[ORDER][FORWARD][IDEMPOTENT] orderNo={} 已存在，currentStatus={}", orderNo, currentStatus);
            return true;
        }

        log.info("[ORDER][FORWARD][COMMIT] orderNo={}，订单创建成功，status=PENDING", orderNo);
        return true;
    }

    /** 补偿动作：取消订单。若创建动作未落库，则写入 CANCELLED 墓碑实现空补偿/防悬挂。 */
    @Transactional
    public boolean cancelOrder(String orderNo) {
        log.warn("[ORDER][COMPENSATE][BEGIN] orderNo={}", orderNo);
        int updated = jdbcTemplate.update("""
                UPDATE t_order
                   SET status='CANCELLED', updated_at=CURRENT_TIMESTAMP(3)
                 WHERE order_no=? AND status<>'CANCELLED'
                """, orderNo);

        if (updated == 0) {
            int inserted = jdbcTemplate.update("""
                    INSERT IGNORE INTO t_order(order_no, product_id, quantity, amount_cents, status)
                    VALUES (?, NULL, 0, 0, 'CANCELLED')
                    """, orderNo);
            if (inserted == 1) {
                log.warn("[ORDER][COMPENSATE][EMPTY] orderNo={} 原订单不存在，登记取消墓碑", orderNo);
            } else {
                log.warn("[ORDER][COMPENSATE][IDEMPOTENT] orderNo={} 已经取消", orderNo);
            }
        } else {
            log.warn("[ORDER][COMPENSATE][COMMIT] orderNo={} 已更新为 CANCELLED", orderNo);
        }
        return true;
    }

    /** 最后一个正向节点：支付完成后把订单标记为 PAID。失败将触发前面节点的逆序补偿。 */
    @Transactional
    public boolean markPaid(String orderNo, String failAt) {
        log.info("[ORDER][FORWARD][MARK_PAID][BEGIN] orderNo={}, failAt={}", orderNo, failAt);
        if ("ORDER_UPDATE".equalsIgnoreCase(failAt)) {
            log.error("[ORDER][FORWARD][SIMULATED_FAILURE] orderNo={}，模拟订单状态更新失败", orderNo);
            throw new IllegalStateException("模拟订单状态更新失败");
        }

        int updated = jdbcTemplate.update("""
                UPDATE t_order
                   SET status='PAID', updated_at=CURRENT_TIMESTAMP(3)
                 WHERE order_no=? AND status='PENDING'
                """, orderNo);
        if (updated == 1) {
            log.info("[ORDER][FORWARD][MARK_PAID][COMMIT] orderNo={}，订单状态变更为 PAID", orderNo);
            return true;
        }

        String status = findStatus(orderNo);
        if ("PAID".equals(status)) {
            log.warn("[ORDER][FORWARD][MARK_PAID][IDEMPOTENT] orderNo={} 已是 PAID", orderNo);
            return true;
        }
        throw new IllegalStateException("订单无法更新为 PAID，orderNo=" + orderNo + ", currentStatus=" + status);
    }

    private String findStatus(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM t_order WHERE order_no=?", orderNo);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("status"));
    }
}
