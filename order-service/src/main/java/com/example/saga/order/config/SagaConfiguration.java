package com.example.saga.order.config;

import javax.sql.DataSource;

import org.apache.seata.saga.engine.config.DbStateMachineConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SagaConfiguration {

    /**
     * Seata 2.6.0 orders Saga configuration after the pre-Boot-4 JDBC auto-configuration.
     * Declare the config here so its creation does not depend on that ordering.
     * The starter still creates the engine and registers it for recovery.
     */
    @Bean
    @ConfigurationProperties(prefix = "seata.saga.state-machine")
    public DbStateMachineConfig dbStateMachineConfig(
            DataSource dataSource,
            @Value("${spring.application.name}") String applicationId,
            @Value("${seata.tx-service-group}") String txServiceGroup) {
        DbStateMachineConfig config = new DbStateMachineConfig();
        config.setDataSource(dataSource);
        config.setApplicationId(applicationId);
        config.setTxServiceGroup(txServiceGroup);
        return config;
    }
}
