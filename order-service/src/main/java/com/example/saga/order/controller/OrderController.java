package com.example.saga.order.controller;

import com.example.saga.api.CheckoutRequest;
import com.example.saga.api.CheckoutResult;
import com.example.saga.api.OrderView;
import com.example.saga.order.service.OrderQueryService;
import com.example.saga.order.service.OrderSagaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderSagaService orderSagaService;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderSagaService orderSagaService, OrderQueryService orderQueryService) {
        this.orderSagaService = orderSagaService;
        this.orderQueryService = orderQueryService;
    }

    /** 一次请求启动一条 Seata Saga 状态机。 */
    @PostMapping("/checkout")
    public CheckoutResult checkout(@RequestBody CheckoutRequest request) {
        return orderSagaService.checkout(request);
    }

    @GetMapping("/{orderNo}")
    public OrderView getOrder(@PathVariable String orderNo) {
        return orderQueryService.getOrder(orderNo);
    }
}
