package io.github.ajayaj724.tradecore.risk;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RiskProperties.class)
class RiskConfig {}
