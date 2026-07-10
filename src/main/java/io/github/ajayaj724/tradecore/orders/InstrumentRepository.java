package io.github.ajayaj724.tradecore.orders;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

interface InstrumentRepository extends ListCrudRepository<Instrument, String> {

    List<Instrument> findAllByOrderBySymbolAsc();
}
