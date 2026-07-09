package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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

// Fresh engine so the book is empty: the market buy has no liquidity to cross, giving a single,
// race-free event path (OrderCancelled only). Crossing/partial fills are pinned at the venue layer.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class MarketOrderEndToEndIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    MarketOrderEndToEndIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private long available(String a) {
        long settled = jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
        long held = jdbc.sql("select coalesce(sum(unit_price*remaining_qty),0) from risk.cash_hold where account = :a")
                .param("a", a)
                .query(Long.class)
                .single();
        return settled - held;
    }

    @Test
    void aMarketBuyWithNoLiquidityIsCancelledAndReleasesTheCapHold() throws Exception {
        long before = available("trader1");

        // MARKET buy, protective cap 10500, qty 5. Risk reserves cap*qty = 52500 up front.
        String marketBuy = "{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10500,\"quantity\":5,\"type\":\"MARKET\"}";
        String body = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "mkt-noliq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(marketBuy))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        // Empty book -> IOC fills nothing -> the whole order is cancelled and the reserved cap is freed.
        // (The hold is reserved synchronously by risk.check, but the async cancel can release it before
        // the test thread observes it, so we assert only the settled end state.)
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            mvc.perform(get("/api/v1/orders/{id}", id).with(trader("trader1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.filledQty").value(0));
            assertThat(available("trader1")).isEqualTo(before);
        });
    }
}
