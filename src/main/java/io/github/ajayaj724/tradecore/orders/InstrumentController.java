package io.github.ajayaj724.tradecore.orders;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instruments")
class InstrumentController {

    private final OrderService service;

    InstrumentController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRADER','OPS','ADMIN')")
    ResponseEntity<List<Instrument>> list() {
        return ResponseEntity.ok(service.instruments());
    }
}
