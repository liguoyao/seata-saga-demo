package com.example.saga.order.controller;

import org.apache.seata.saga.engine.StateMachineEngine;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立故障演示入口，每次使用新业务键。 */
@RestController
@RequestMapping("/api/saga-faults")
public class SagaFaultController {
    private static final Set<String> SCENARIOS =
        Set.of("SUCCESS", "FORWARD_EXCEPTION", "READ_TIMEOUT", "CONNECT_FAILURE", "COMPENSATION_EXCEPTION");
    private final StateMachineEngine engine;

    public SagaFaultController(StateMachineEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/{scenario}")
    public Map<String, Object> run(@PathVariable String scenario) {
        String mode = scenario.toUpperCase(java.util.Locale.ROOT);
        if (!SCENARIOS.contains(mode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "未知场景: " + mode);
        }
        String key = "FAULT-" + UUID.randomUUID();
        var instance = engine.startWithBusinessKey("faultScenarioSaga", "000001", key,
            new java.util.HashMap<>(Map.of("scenario", mode, "testKey", key)));
        return Map.of("testKey", key, "instanceId", instance.getId(), "status", String.valueOf(instance.getStatus()),
            "compensationStatus", String.valueOf(instance.getCompensationStatus()));
    }
}
