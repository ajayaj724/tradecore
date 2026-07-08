package io.github.ajayaj724.tradecore.orders;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "orders", name = "instrument")
record Instrument(@Id String symbol, String name) {}
