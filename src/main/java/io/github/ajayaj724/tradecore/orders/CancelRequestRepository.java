package io.github.ajayaj724.tradecore.orders;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

interface CancelRequestRepository extends ListCrudRepository<CancelRequest, Long> {

    boolean existsByOrderIdAndStatus(long orderId, CancelRequestStatus status);

    List<CancelRequest> findByStatusOrderByIdDesc(CancelRequestStatus status);
}
