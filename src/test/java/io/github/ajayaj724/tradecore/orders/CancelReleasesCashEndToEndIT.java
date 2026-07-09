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

// Fresh engine + fresh seeds so the resting order and its cash flow are deterministic.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CancelReleasesCashEndToEndIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    CancelReleasesCashEndToEndIT(MockMvc mvc, JdbcClient jdbc) {
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
    void cancellingARestingBuyReleasesTheReservedCash() throws Exception {
        long before = available("trader1");

        // A lone BUY with no crossing SELL simply rests -> ACCEPTED, reserving 9000 * 5 = 45000.
        String body = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "cxl-cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":9000,\"quantity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(available("trader1")).isEqualTo(before - 45000L); // reserved synchronously on submit

        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(trader("trader1")))
                .andExpect(status().isAccepted()); // 202: accepted for async cancellation

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            mvc.perform(get("/api/v1/orders/{id}", id).with(trader("trader1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(available("trader1")).isEqualTo(before); // the whole hold is credited back
        });
    }
}
