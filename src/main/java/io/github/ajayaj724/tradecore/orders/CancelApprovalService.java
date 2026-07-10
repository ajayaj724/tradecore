package io.github.ajayaj724.tradecore.orders;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Four-eyes ops cancellation (ADR-0024): an ops cancel parks a PENDING request; a different
 * ops user approves (which executes the actual cancel) or declines. Trader self-cancels never
 * come through here.
 */
@Service
class CancelApprovalService {

    private final CancelRequestRepository requests;
    private final OrderRepository orders;
    private final AuditRepository audit;
    private final OrderService orderService;
    private final Clock clock;

    CancelApprovalService(
            CancelRequestRepository requests,
            OrderRepository orders,
            AuditRepository audit,
            OrderService orderService,
            Clock clock) {
        this.requests = requests;
        this.orders = orders;
        this.audit = audit;
        this.orderService = orderService;
        this.clock = clock;
    }

    /** Park a cancel request for a working order; returns the (unchanged) order. */
    @Transactional
    Order request(long orderId, String requestedBy) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.status() != OrderStatus.ACCEPTED && order.status() != OrderStatus.PARTIALLY_FILLED) {
            throw new OrderNotCancellableException(orderId, order.status());
        }
        if (requests.existsByOrderIdAndStatus(orderId, CancelRequestStatus.PENDING)) {
            throw new CancelRequestConflictException("a cancel request is already pending for order " + orderId);
        }
        // The partial unique index (one PENDING per order) backs this check against races.
        requests.save(CancelRequest.pending(orderId, requestedBy, clock.instant()));
        record(order, "CANCEL_APPROVAL_REQUESTED", requestedBy);
        return order;
    }

    /** Second pair of eyes: the approver must differ from the requester; executes the cancel. */
    @Transactional
    void approve(long requestId, String approver) {
        CancelRequest request = decidable(requestId);
        if (request.requestedBy().equals(approver)) {
            throw new AccessDeniedException("four-eyes: the requester may not approve their own cancellation");
        }
        requests.save(request.decided(CancelRequestStatus.APPROVED, approver, clock.instant()));
        Order order = orderService.cancel(request.orderId(), approver, approver, true);
        record(order, "CANCEL_APPROVED", approver);
    }

    @Transactional
    void decline(long requestId, String decidedBy) {
        CancelRequest request = decidable(requestId);
        requests.save(request.decided(CancelRequestStatus.DECLINED, decidedBy, clock.instant()));
        Order order =
                orders.findById(request.orderId()).orElseThrow(() -> new OrderNotFoundException(request.orderId()));
        record(order, "CANCEL_DECLINED", decidedBy);
    }

    @Transactional(readOnly = true)
    List<CancelRequestResponse> pending() {
        return requests.findByStatusOrderByIdDesc(CancelRequestStatus.PENDING).stream()
                .map(this::toResponse)
                .toList();
    }

    private CancelRequest decidable(long requestId) {
        CancelRequest request =
                requests.findById(requestId).orElseThrow(() -> new CancelRequestNotFoundException(requestId));
        if (request.status() != CancelRequestStatus.PENDING) {
            throw new CancelRequestConflictException("cancel request " + requestId + " is already decided");
        }
        return request;
    }

    private CancelRequestResponse toResponse(CancelRequest request) {
        Order order =
                orders.findById(request.orderId()).orElseThrow(() -> new OrderNotFoundException(request.orderId()));
        return new CancelRequestResponse(
                Objects.requireNonNull(request.id()),
                request.orderId(),
                order.account(),
                order.symbol(),
                request.requestedBy(),
                request.status().name());
    }

    private void record(Order order, String action, String principal) {
        audit.save(new AuditRecord(
                null, Objects.requireNonNull(order.id()), order.account(), action, principal, clock.instant(), null));
    }
}
