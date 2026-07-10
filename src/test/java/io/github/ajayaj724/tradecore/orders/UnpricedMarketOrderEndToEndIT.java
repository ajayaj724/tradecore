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

// Fresh engine so the book contains exactly the liquidity this test rests.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class UnpricedMarketOrderEndToEndIT {

    private final MockMvc mvc;

    @Autowired
    UnpricedMarketOrderEndToEndIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void anUnpricedMarketBuyIsCappedByTheCollarAndFillsAtTheBookPrice() throws Exception {
        // trader2 rests a SELL 3 @ 100.00 — the only liquidity on the fresh book.
        mvc.perform(post("/api/v1/orders")
                        .with(trader("trader2"))
                        .header("Idempotency-Key", "unp-rest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"SELL\",\"price\":10000,\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // trader1 sends a MARKET buy with NO price. The seeded 10000 reference plus the 5%
        // collar makes the effective protective cap 10500 — visible in the response.
        String body = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "unp-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"quantity\":2,\"type\":\"MARKET\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.price").value(10500))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        // It crosses the resting ask and fills fully at the book price (10000, not the cap).
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> mvc.perform(
                        get("/api/v1/orders/{id}", id).with(trader("trader1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQty").value(2)));
    }
}
