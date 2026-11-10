-- MySQL 8；仅修复日志中这个旧实例缺少 MarkOrderPaid 补偿的问题。
-- 执行前：停止所有连接该订单库、可恢复该实例的 order-service 进程。
-- 使用同一个数据库会话逐段执行；检查最后结果后手动 COMMIT，否则 ROLLBACK。
-- 不删除实例、不改 TC 状态、不改业务订单状态、不移除 failAt。
USE order_db_saga;
SET @repair_xid = '10.11.2.102:8091:27863493623095328';
SET @repair_definition_id = REPLACE(UUID(), '-', '');

START TRANSACTION;

-- 1. 锁定并查看旧实例及其实际使用的定义。必须返回目标实例。
SELECT i.id, i.business_key, i.machine_id, i.status, i.compensation_status,
       d.name, d.ver,
       JSON_EXTRACT(d.content, '$.States.MarkOrderPaid') AS mark_order_paid,
       JSON_EXTRACT(d.content, '$.States.CancelOrder') AS cancel_order
FROM seata_state_machine_inst i
JOIN seata_state_machine_def d ON d.id = i.machine_id
WHERE i.id = @repair_xid
FOR UPDATE;

-- 2. 仅在补偿配置缺失且 CancelOrder 节点存在时复制定义。
-- 保留旧定义和其他节点，只补缺失字段；其他实例仍引用旧定义。
INSERT INTO seata_state_machine_def
    (id, name, tenant_id, app_name, type, comment_, ver, gmt_create,
     status, content, recover_strategy)
SELECT @repair_definition_id, d.name, d.tenant_id, d.app_name, d.type,
       d.comment_, d.ver, d.gmt_create, d.status,
       JSON_SET(d.content, '$.States.MarkOrderPaid.CompensateState', 'CancelOrder'),
       d.recover_strategy
FROM seata_state_machine_def d
JOIN seata_state_machine_inst i ON i.machine_id = d.id
WHERE i.id = @repair_xid
  AND i.parent_id IS NULL
  AND d.name = 'placeOrderSaga'
  AND JSON_UNQUOTE(JSON_EXTRACT(d.content, '$.States.MarkOrderPaid.Type')) = 'ServiceTask'
  AND JSON_UNQUOTE(JSON_EXTRACT(d.content, '$.States.CancelOrder.ServiceName')) = 'orderAction'
  AND JSON_UNQUOTE(JSON_EXTRACT(d.content, '$.States.CancelOrder.ServiceMethod')) = 'cancelOrder'
  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(d.content,
        '$.States.MarkOrderPaid.CompensateState')), '') = '';
SELECT ROW_COUNT() AS copied_definitions_expected_1;

-- 3. 仅当副本成功创建时，将目标实例绑定到副本。
UPDATE seata_state_machine_inst i
JOIN seata_state_machine_def d ON d.id = @repair_definition_id
SET i.machine_id = d.id
WHERE i.id = @repair_xid;
SELECT ROW_COUNT() AS rebound_instances_expected_1;

-- 4. 确认配置已为 CancelOrder，且前面两个计数都为 1。
SELECT i.id, i.business_key, i.machine_id, i.status, i.compensation_status,
       JSON_UNQUOTE(JSON_EXTRACT(d.content,
           '$.States.MarkOrderPaid.CompensateState')) AS compensation_expected_CancelOrder
FROM seata_state_machine_inst i
JOIN seata_state_machine_def d ON d.id = i.machine_id
WHERE i.id = @repair_xid;

-- 确认无误后，在当前会话执行 COMMIT；否则执行 ROLLBACK。
-- COMMIT;
-- ROLLBACK;
-- 提交后启动更新后的 order-service，观察该 XID 的恢复结果。
