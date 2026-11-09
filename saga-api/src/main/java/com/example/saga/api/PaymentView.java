package com.example.saga.api;

public record PaymentView(String orderNo, Long amountCents, String state) {
}
