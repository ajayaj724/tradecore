package io.github.ajayaj724.tradecore.orders;

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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Fresh context => fresh in-memory engine and fresh seeded balances, so the crossing is clean and
// not contaminated by resting ACME orders other tests leave in the shared engine bean.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OrderFillEndToEndIT {

    private final MockMvc mvc;

    @Autowired
    OrderFillEndToEndIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String username) {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private long submit(String user, String side, String key) throws Exception {
        String body = mvc.perform(post("/api/v1/orders")
                        .with(trader(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"" + side + "\",\"price\":10000,\"quantity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void oneOrderFillsEndToEnd() throws Exception {
        submit("trader2", "SELL", "e2e-sell"); // resting maker
        long buyId = submit("trader1", "BUY", "e2e-buy"); // crossing taker

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> mvc.perform(
                        get("/api/v1/orders/{id}", buyId).with(trader("trader1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQty").value(5)));
    }
}
