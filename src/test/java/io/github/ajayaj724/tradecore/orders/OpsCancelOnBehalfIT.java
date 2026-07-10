package io.github.ajayaj724.tradecore.orders;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OpsCancelOnBehalfIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    OpsCancelOnBehalfIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private static RequestPostProcessor ops(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_OPS"));
    }

    private long submitResting(String key) throws Exception {
        String body = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":5000,\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void opsCancelsAnotherAccountsOrderAndTheAuditNamesTheOpsPrincipal() throws Exception {
        long id = submitResting("ops-cxl-1");

        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(ops("ops-canceller")))
                .andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> mvc.perform(
                        get("/api/v1/orders/{id}", id).with(trader("trader1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")));

        String principal = jdbc.sql(
                        "select principal from orders.audit where order_id = :o and action = 'CANCEL_REQUESTED'")
                .param("o", id)
                .query(String.class)
                .single();
        assertThat(principal).isEqualTo("ops-canceller");
    }

    @Test
    void opsCancelStillRespectsOrderState() throws Exception {
        // Far-over-cash BUY is risk-rejected → terminal, not cancellable even for ops.
        String body = mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "ops-cxl-terminal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1000000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(ops("ops-canceller")))
                .andExpect(status().isConflict());
    }
}
