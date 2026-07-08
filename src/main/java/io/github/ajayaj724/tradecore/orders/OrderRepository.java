package io.github.ajayaj724.tradecore.orders;

import org.springframework.data.repository.ListCrudRepository;

interface OrderRepository extends ListCrudRepository<Order, Long> {}
