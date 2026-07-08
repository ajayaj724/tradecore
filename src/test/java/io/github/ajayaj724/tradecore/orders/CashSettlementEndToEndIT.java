package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Fresh context => fresh engine + fresh seeds, so the crossing and the cash flow are deterministic.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CashSettlementEndToEndIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    CashSettlementEndToEndIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private void submit(String user, String side, long price, String key) throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"" + side + "\",\"price\":" + price
                                + ",\"quantity\":5}"))
                .andExpect(status().isCreated());
    }

    private long ledgerBalance(String a) {
        return jdbc.sql("select coalesce(sum(amount),0) from ledger.posting where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    private long settled(String a) {
        return jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    private long available(String a) {
        long held = jdbc.sql("select coalesce(sum(unit_price*remaining_qty),0) from risk.cash_hold where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
        return settled(a) - held;
    }

    @Test
    void fillSettlesCashAndRefundsOverReservation() throws Exception {
        submit("trader2", "SELL", 9000L, "cash-sell"); // maker rests @ 90.00
        submit("trader1", "BUY", 10000L, "cash-buy"); // taker limit 100.00 -> fills @ 90.00

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(ledgerBalance("trader1")).isEqualTo(100000000L - 45000L); // bought 5 @ 9000
            assertThat(ledgerBalance("trader2")).isEqualTo(100000000L + 45000L);
            assertThat(settled("trader1")).isEqualTo(ledgerBalance("trader1")); // reconciliation-lite
            // hold released -> available reflects only the 45000 spent, not the 50000 reserved at limit
            assertThat(available("trader1")).isEqualTo(100000000L - 45000L);
        });
    }
}
