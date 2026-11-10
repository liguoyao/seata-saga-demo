-- 已有数据库执行一次；新建库也可重复执行。不会重置已使用的优惠券。
USE order_db_saga;
CREATE TABLE IF NOT EXISTS t_coupon (
    coupon_code VARCHAR(48) NOT NULL PRIMARY KEY,
    discount_cents BIGINT NOT NULL,
    used_order_no VARCHAR(48) NULL,
    CONSTRAINT chk_coupon_discount CHECK (discount_cents > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定面额单次优惠券';

CREATE TABLE IF NOT EXISTS t_coupon_operation (
    order_no VARCHAR(48) NOT NULL PRIMARY KEY,
    coupon_code VARCHAR(48) NOT NULL,
    original_cents BIGINT NOT NULL,
    payable_cents BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL COMMENT 'REDEEMING/USED/RELEASED'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券核销与退券幂等流水';

INSERT IGNORE INTO t_coupon(coupon_code, discount_cents) VALUES ('SAVE500', 500), ('FREE1000', 1000);
