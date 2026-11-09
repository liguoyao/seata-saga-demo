package com.example.saga.order.service;

import com.example.saga.api.OrderView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class OrderQueryService {
    private final JdbcTemplate jdbcTemplate;

    public OrderQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OrderView getOrder(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT order_no, product_id, quantity, amount_cents, status
                  FROM t_order WHERE order_no=?
                """, orderNo);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "订单不存在：" + orderNo);
        }
        Map<String, Object> row = rows.get(0);
        Object quantity = row.get("quantity");
        Object amount = row.get("amount_cents");
        return new OrderView(
                String.valueOf(row.get("order_no")),
                row.get("product_id") == null ? null : String.valueOf(row.get("product_id")),
                quantity == null ? null : ((Number) quantity).intValue(),
                amount == null ? null : ((Number) amount).longValue(),
                String.valueOf(row.get("status")));
    }
}
