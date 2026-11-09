package com.example.saga.inventory.service;

import com.example.saga.api.InventoryCommand;
import com.example.saga.api.InventoryView;
import com.example.saga.api.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 库存服务自己的本地事务边界。
 *
 * 注意：这里没有 @GlobalTransactional，也没有把库存数据库交给 Seata AT 代理。
 * Saga 的每个参与者都提交自己的本地事务，失败后由补偿方法做业务逆操作。
 */
@Service
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private final JdbcTemplate jdbcTemplate;

    public InventoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 正向动作：扣减库存。一个 orderNo 只允许成功扣减一次。 */
    @Transactional
    public OperationResult deduct(InventoryCommand command) {
        String orderNo = command.orderNo();
        int quantity = command.quantity() == null ? 0 : command.quantity();
        log.info("[INVENTORY][FORWARD][BEGIN] orderNo={}, productId={}, quantity={}, failAt={}",
                orderNo, command.productId(), quantity, command.failAt());

        if ("INVENTORY".equalsIgnoreCase(command.failAt())) {
            log.error("[INVENTORY][FORWARD][SIMULATED_FAILURE] orderNo={}，模拟库存服务失败", orderNo);
            throw new IllegalStateException("模拟库存扣减失败：库存服务拒绝本次请求");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        // 先插入操作记录，再变更库存。唯一键 order_no 用于请求幂等。
        // 如果后续库存不足，当前本地事务回滚，操作记录也会一起回滚。
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO t_inventory_operation(order_no, product_id, quantity, state)
                VALUES (?, ?, ?, 'DEDUCTING')
                """, orderNo, command.productId(), quantity);

        if (inserted == 0) {
            String existingState = findOperationState(orderNo);
            if ("DEDUCTED".equals(existingState)) {
                log.warn("[INVENTORY][IDEMPOTENT] orderNo={} 已扣减，跳过重复扣减", orderNo);
                return new OperationResult(true, existingState, "重复请求，库存已扣减");
            }
            // RESTORED 表示补偿先于正向请求到达，必须阻止迟到的扣减（防悬挂）。
            throw new IllegalStateException("库存操作已被补偿或状态异常，拒绝悬挂扣减，orderNo=" + orderNo
                    + ", state=" + existingState);
        }

        int rows = jdbcTemplate.update("""
                UPDATE t_inventory
                   SET available = available - ?, updated_at = CURRENT_TIMESTAMP(3)
                 WHERE product_id = ? AND available >= ?
                """, quantity, command.productId(), quantity);
        if (rows != 1) {
            log.error("[INVENTORY][FORWARD][FAILED] orderNo={}, productId={}, quantity={}，库存不足或商品不存在",
                    orderNo, command.productId(), quantity);
            throw new IllegalStateException("库存不足或商品不存在：" + command.productId());
        }

        jdbcTemplate.update("UPDATE t_inventory_operation SET state='DEDUCTED' WHERE order_no=?", orderNo);
        Integer left = jdbcTemplate.queryForObject(
                "SELECT available FROM t_inventory WHERE product_id=?", Integer.class, command.productId());
        log.info("[INVENTORY][FORWARD][COMMIT] orderNo={}, productId={}, deducted={}, remaining={}",
                orderNo, command.productId(), quantity, left);
        return new OperationResult(true, "DEDUCTED", "库存扣减成功");
    }

    /** 反向补偿：恢复库存。允许空补偿，并通过 RESTORED 标记防止后续迟到的正向扣减。 */
    @Transactional
    public OperationResult restore(InventoryCommand command) {
        String orderNo = command.orderNo();
        int quantity = command.quantity() == null ? 0 : command.quantity();
        log.warn("[INVENTORY][COMPENSATE][BEGIN] orderNo={}, productId={}, quantity={}",
                orderNo, command.productId(), quantity);

        // 若正向服务从未落库，先写 RESTORED 墓碑。迟到的 deduct 会读到该状态并拒绝执行。
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO t_inventory_operation(order_no, product_id, quantity, state)
                VALUES (?, ?, ?, 'RESTORED')
                """, orderNo, command.productId(), quantity);

        if (inserted == 1) {
            log.warn("[INVENTORY][COMPENSATE][EMPTY] orderNo={} 未发现正向扣减，登记空补偿标记", orderNo);
            return new OperationResult(true, "RESTORED", "空补偿成功，已登记防悬挂标记");
        }

        String existingState = findOperationState(orderNo);
        if ("RESTORED".equals(existingState)) {
            log.warn("[INVENTORY][COMPENSATE][IDEMPOTENT] orderNo={} 已恢复，跳过重复补偿", orderNo);
            return new OperationResult(true, existingState, "重复补偿，库存已恢复");
        }
        if (!"DEDUCTED".equals(existingState)) {
            throw new IllegalStateException("库存补偿遇到非预期状态，orderNo=" + orderNo + ", state=" + existingState);
        }

        jdbcTemplate.update("""
                UPDATE t_inventory
                   SET available = available + ?, updated_at = CURRENT_TIMESTAMP(3)
                 WHERE product_id = ?
                """, quantity, command.productId());
        jdbcTemplate.update("UPDATE t_inventory_operation SET state='RESTORED' WHERE order_no=?", orderNo);
        Integer now = jdbcTemplate.queryForObject(
                "SELECT available FROM t_inventory WHERE product_id=?", Integer.class, command.productId());
        log.warn("[INVENTORY][COMPENSATE][COMMIT] orderNo={}, restored={}, available={}", orderNo, quantity, now);
        return new OperationResult(true, "RESTORED", "库存恢复成功");
    }

    public InventoryView getInventory(String productId) {
        List<InventoryView> rows = jdbcTemplate.query(
                "SELECT product_id, available FROM t_inventory WHERE product_id=?",
                (rs, rowNum) -> new InventoryView(rs.getString("product_id"), rs.getInt("available")),
                productId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("商品不存在：" + productId);
        }
        return rows.get(0);
    }

    private String findOperationState(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state FROM t_inventory_operation WHERE order_no=?", orderNo);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("state"));
    }
}
