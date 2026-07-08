package io.github.ajayaj724.tradecore.orders;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "orders", name = "audit")
record AuditRecord(
        @Id @Nullable Long id,
        long orderId,
        String account,
        String action,
        String principal,
        Instant occurredAt,
        @Nullable String detail) {}
