package com.example.saga.payment.controller;

import com.example.saga.api.OperationResult;
import com.example.saga.api.PaymentCommand;
import com.example.saga.api.PaymentView;
import com.example.saga.payment.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/internal/payment/pay")
    public OperationResult pay(@RequestBody PaymentCommand command) {
        return paymentService.pay(command);
    }

    @PostMapping("/internal/payment/refund")
    public OperationResult refund(@RequestBody PaymentCommand command) {
        return paymentService.refund(command);
    }

    @GetMapping("/api/payments/{orderNo}")
    public PaymentView getPayment(@PathVariable String orderNo) {
        return paymentService.getPayment(orderNo);
    }
}
