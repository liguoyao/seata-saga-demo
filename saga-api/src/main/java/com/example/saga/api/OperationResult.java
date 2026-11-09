package com.example.saga.api;

public record OperationResult(boolean success, String state, String message) {
}
