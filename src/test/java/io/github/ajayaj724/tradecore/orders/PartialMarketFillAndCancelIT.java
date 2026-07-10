package io.github.ajayaj724.tradecore.orders;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.ajayaj724.tradecore.TestcontainersConfig;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A partial market fill emits TradeExecuted and OrderCancelled for the same order; their async
 * listeners race on the row's optimistic lock. Whatever the interleaving, the terminal state must
 * be CANCELLED *with the fill booked* — without waiting for a restart-republish.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PartialMarketFillAndCancelIT {

    private final MockMvc mvc;

    @Autowired
    PartialMarketFillAndCancelIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void aPartialMarketFillBooksTheFillOnBothOrdersDespiteTheCancelRace() throws Exception {
        String restBody = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader2"))
                        .header("Idempotency-Key", "pfc-rest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"SELL\",\"price\":10000,\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long askId = ((Number) JsonPath.read(restBody, "$.id")).longValue();

        // Unpriced MARKET buy for MORE than the resting liquidity: fills 3, cancels 2.
        String buyBody = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "pfc-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"quantity\":5,\"type\":\"MARKET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long buyId = ((Number) JsonPath.read(buyBody, "$.id")).longValue();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            mvc.perform(get("/api/v1/orders/{id}", buyId).with(trader("trader1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.filledQty").value(3));
            mvc.perform(get("/api/v1/orders/{id}", askId).with(trader("trader2")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FILLED"))
                    .andExpect(jsonPath("$.filledQty").value(3));
        });
    }
}
