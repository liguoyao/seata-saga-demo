package com.example.saga.inventory.controller;

import com.example.saga.api.InventoryCommand;
import com.example.saga.api.InventoryView;
import com.example.saga.api.OperationResult;
import com.example.saga.inventory.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class InventoryController {
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/internal/inventory/deduct")
    public OperationResult deduct(@RequestBody InventoryCommand command) {
        return inventoryService.deduct(command);
    }

    @PostMapping("/internal/inventory/restore")
    public OperationResult restore(@RequestBody InventoryCommand command) {
        return inventoryService.restore(command);
    }

    @GetMapping("/api/inventory/{productId}")
    public InventoryView getInventory(@PathVariable String productId) {
        return inventoryService.getInventory(productId);
    }
}
