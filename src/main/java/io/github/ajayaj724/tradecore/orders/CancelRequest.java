package io.github.ajayaj724.tradecore.orders;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "orders", name = "cancel_request")
record CancelRequest(
        @Id @Nullable Long id,
        long orderId,
        String requestedBy,
        Instant requestedAt,
        CancelRequestStatus status,
        @Nullable String decidedBy,
        @Nullable Instant decidedAt,
        @Version @Nullable Long version) {

    static CancelRequest pending(long orderId, String requestedBy, Instant requestedAt) {
        return new CancelRequest(
                null, orderId, requestedBy, requestedAt, CancelRequestStatus.PENDING, null, null, null);
    }

    CancelRequest decided(CancelRequestStatus outcome, String by, Instant at) {
        return new CancelRequest(id, orderId, requestedBy, requestedAt, outcome, by, at, version);
    }
}
