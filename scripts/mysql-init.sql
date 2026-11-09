-- ============================================================================
-- Seata Saga + Spring Boot 4.0.7 Demo 初始化 SQL
-- 三个业务服务使用三个独立 Schema；Saga 状态机日志仅放在订单服务数据库。
-- docker-entrypoint-initdb.d 只会在 MySQL 数据卷首次初始化时自动执行。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS order_db_saga
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS inventory_db_saga
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS payment_db_saga
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ------------------------------ ORDER DB ------------------------------------
USE order_db_saga;

CREATE TABLE IF NOT EXISTS t_order (
    order_no      VARCHAR(48)  NOT NULL COMMENT '业务订单号，也是 Saga businessKey',
    product_id    VARCHAR(64)  NULL,
    quantity      INT          NOT NULL DEFAULT 0,
    amount_cents  BIGINT       NOT NULL DEFAULT 0 COMMENT '金额，单位分',
    status        VARCHAR(20)  NOT NULL COMMENT 'PENDING/PAID/CANCELLED',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_no),
    KEY idx_order_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单业务表';

-- 以下三张表是 Seata Saga 状态机存储表。请勿在业务逻辑中手动删除运行中的实例。
CREATE TABLE IF NOT EXISTS seata_state_machine_def (
    id                VARCHAR(32)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    tenant_id         VARCHAR(32)  NOT NULL,
    app_name          VARCHAR(32)  NOT NULL,
    type              VARCHAR(20)  NULL,
    comment_          VARCHAR(255) NULL,
    ver               VARCHAR(16)  NOT NULL,
    gmt_create        DATETIME(3)  NOT NULL,
    status            VARCHAR(2)   NOT NULL,
    content           TEXT         NULL,
    recover_strategy  VARCHAR(16)  NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Saga 状态机定义';

CREATE TABLE IF NOT EXISTS seata_state_machine_inst (
    id                  VARCHAR(128) NOT NULL,
    machine_id          VARCHAR(32)  NOT NULL,
    tenant_id           VARCHAR(32)  NOT NULL,
    parent_id           VARCHAR(128) NULL,
    gmt_started         DATETIME(3)  NOT NULL,
    business_key        VARCHAR(48)  NULL,
    start_params        TEXT         NULL,
    gmt_end             DATETIME(3)  NULL,
    excep               BLOB         NULL,
    end_params          TEXT         NULL,
    status              VARCHAR(2)   NULL,
    compensation_status VARCHAR(2)   NULL,
    is_running          TINYINT(1)   NULL,
    gmt_updated         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_saga_inst_business (business_key, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Saga 状态机实例';

CREATE TABLE IF NOT EXISTS seata_state_inst (
    id                         VARCHAR(48)  NOT NULL,
    machine_inst_id            VARCHAR(128) NOT NULL,
    name                       VARCHAR(128) NOT NULL,
    type                       VARCHAR(20)  NULL,
    service_name               VARCHAR(128) NULL,
    service_method             VARCHAR(128) NULL,
    service_type               VARCHAR(16)  NULL,
    business_key               VARCHAR(48)  NULL,
    state_id_compensated_for    VARCHAR(50)  NULL,
    state_id_retried_for        VARCHAR(50)  NULL,
    gmt_started                DATETIME(3)  NOT NULL,
    is_for_update              TINYINT(1)   NULL,
    input_params               TEXT         NULL,
    output_params              TEXT         NULL,
    status                     VARCHAR(2)   NOT NULL,
    excep                      BLOB         NULL,
    gmt_updated                DATETIME(3)  NULL,
    gmt_end                    DATETIME(3)  NULL,
    PRIMARY KEY (id, machine_inst_id),
    KEY idx_saga_state_inst_machine (machine_inst_id),
    KEY idx_saga_state_inst_biz (business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Saga 状态节点执行日志';

-- ---------------------------- INVENTORY DB ----------------------------------
USE inventory_db_saga;

CREATE TABLE IF NOT EXISTS t_inventory (
    product_id  VARCHAR(64) NOT NULL,
    available   INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (product_id),
    CONSTRAINT chk_inventory_non_negative CHECK (available >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表';

CREATE TABLE IF NOT EXISTS t_inventory_operation (
    order_no    VARCHAR(48) NOT NULL,
    product_id  VARCHAR(64) NOT NULL,
    quantity    INT         NOT NULL,
    state       VARCHAR(20) NOT NULL COMMENT 'DEDUCTED/RESTORED',
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_no),
    KEY idx_inventory_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存正向/补偿幂等流水';

INSERT INTO t_inventory(product_id, available)
VALUES ('SKU-1001', 100)
ON DUPLICATE KEY UPDATE product_id=VALUES(product_id);

-- ----------------------------- PAYMENT DB -----------------------------------
USE payment_db_saga;

CREATE TABLE IF NOT EXISTS t_payment_operation (
    order_no      VARCHAR(48) NOT NULL,
    amount_cents  BIGINT      NOT NULL DEFAULT 0,
    state         VARCHAR(20) NOT NULL COMMENT 'PAID/REFUNDED',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付/退款幂等流水';
