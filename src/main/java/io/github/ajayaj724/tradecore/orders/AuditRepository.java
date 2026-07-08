package io.github.ajayaj724.tradecore.orders;

import org.springframework.data.repository.ListCrudRepository;

interface AuditRepository extends ListCrudRepository<AuditRecord, Long> {}
