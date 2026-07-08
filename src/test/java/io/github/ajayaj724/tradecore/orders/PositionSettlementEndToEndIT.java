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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PositionSettlementEndToEndIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    PositionSettlementEndToEndIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private void submit(String user, String side, String key) throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"" + side + "\",\"price\":10000,\"quantity\":5}"))
                .andExpect(status().isCreated());
    }

    private long portfolioQty(String a) {
        return jdbc.sql("select coalesce(total_qty,0) from portfolio.position where account = :a and symbol = 'ACME'")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    private long settledHoldings(String a) {
        return jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = 'ACME'")
                .param("a", a)
                .query(Long.class)
                .single();
    }

    @Test
    void fillBuildsPositionAndSettlesHoldings() throws Exception {
        submit("trader2", "SELL", "pos-sell");
        submit("trader1", "BUY", "pos-buy");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(portfolioQty("trader1")).isEqualTo(1005L); // opening 1000 + bought 5
            assertThat(portfolioQty("trader2")).isEqualTo(995L); // opening 1000 - sold 5
            // reconciliation-lite: portfolio and risk share one seeded origin, so they stay equal
            assertThat(settledHoldings("trader1")).isEqualTo(portfolioQty("trader1"));
            assertThat(settledHoldings("trader2")).isEqualTo(portfolioQty("trader2"));
        });
    }
}
