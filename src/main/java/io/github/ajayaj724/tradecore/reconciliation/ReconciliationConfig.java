package io.github.ajayaj724.tradecore.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReconciliationProperties.class)
class ReconciliationConfig {}
