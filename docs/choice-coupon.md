# Choice 与优惠券子状态机

主流程：创建订单 → 扣库存 → couponSaga 子状态机 → 检查子流程结果 → Choice 判断实付金额。

- 实付大于 0：调用支付，订单最终为 `PAID`。
- 实付等于 0：跳过支付，订单最终为 `COMPLETED`，仍扣减商品库存。
- 金额不能为负数；`amountCents` 是优惠前金额，订单成功完成后 `t_order.amount_cents` 保存实付金额。券流水保存原价和实付金额。
- `couponCode` 可省略；提供时，子流程执行校验、核销、确认。固定面额券最多抵扣到 0，不产生负金额或找零；原价为 0 且传券也会核销该券，免费订单建议不传券。
- 示例券 `SAVE500` 抵扣 500 分，`FREE1000` 抵扣 1000 分。每张券同一时间只能由一张订单使用，补偿退券后可供其他订单使用。
- 券在订单服务中实现，无需新增第四个服务；本例不涉及用户归属、有效期、门槛、叠加或优惠券发放。

## 数据库准备

已有数据库先执行 `scripts/coupon-migration.sql`；新数据库的 `scripts/mysql-init.sql` 已包含同样的表与示例券。脚本不会重置已经使用的券。此变更未自动操作远程数据库。

## 请求示例

POST `/api/orders/checkout`，Content-Type 为 `application/json`：

```json
{
  "orderNo": "COUPON-001",
  "productId": "SKU-1001",
  "quantity": 1,
  "amountCents": 1000,
  "couponCode": "SAVE500",
  "failAt": "NONE"
}
```

上例实付 500 分。改成 `amountCents: 500` 可测试优惠后零元；省略 `couponCode` 且金额为 0 可测试直接零元。

每次独立场景使用新的 `orderNo`。成功使用的券不能再给另一订单使用，测试时可新增其他券号。

## 补偿验证

- `failAt: COUPON`：核销本地事务提交后在确认节点抛异常。子流程先退券，父流程再恢复库存、取消订单。
- `failAt: ORDER_UPDATE`：券和支付都完成后抛异常。父流程依次退款（仅付费分支）、调用 `CompensateSubMachine` 退券、恢复库存、取消订单。
- `failAt: PAYMENT`：支付失败后退券并恢复库存、取消订单；零元分支不调用支付，所以不会触发此故障。
- 优惠券不存在或被占用：子流程失败，父流程根据 `couponStatus` 进入补偿，不继续支付。

子流程内部使用 `ReleaseCoupon` 补偿核销；父流程通过 `CompensateSubMachine` 调用子流程补偿。子状态机正常返回也可能状态为失败，因此额外使用 `CouponResultChoice` 判断结果，不能只依赖 `Catch`。

券流水通过订单主键与事务内行锁保护幂等；空补偿写入 `RELEASED` 墓碑，阻止迟到核销。没有移除父实例的 businessKey 唯一约束；重复 checkout 的入口幂等和 Begin 异常收尾仍属于之前讨论的独立问题。

## 自动验证

执行 `mvn -pl order-service -am test`。测试运行真实 Seata JSON 状态机、Spring 本地事务及 H2 MySQL 模式数据库；TC、远程库存和支付被替换为测试对象。真实 MySQL、网络调用与 TC 恢复仍需联调。

节点语法参考：[Seata Saga 官方文档](https://seata.apache.org/docs/user/mode/saga/)。
