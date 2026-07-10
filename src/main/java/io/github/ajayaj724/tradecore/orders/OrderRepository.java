package io.github.ajayaj724.tradecore.orders;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

interface OrderRepository extends ListCrudRepository<Order, Long> {

    List<Order> findByAccountOrderByIdDesc(String account, Limit limit);
}
