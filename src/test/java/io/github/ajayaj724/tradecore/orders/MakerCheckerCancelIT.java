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
import java.util.List;
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

/** Four-eyes ops cancellation (ADR-0024): request by one ops user, decision by another. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MakerCheckerCancelIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    MakerCheckerCancelIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor as(String username, String role) {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(createAuthorityList("ROLE_" + role));
    }

    private long submitResting(String key) throws Exception {
        String body = mvc.perform(post("/api/v1/orders")
                        .with(as("trader1", "TRADER"))
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

    private long requestCancel(long orderId, String opsUser) throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(as(opsUser, "OPS")))
                .andExpect(status().isAccepted());
        return jdbc.sql("select id from orders.cancel_request where order_id = :o and status = 'PENDING'")
                .param("o", orderId)
                .query(Long.class)
                .single();
    }

    @Test
    void opsCancelParksARequestInsteadOfCancelling() throws Exception {
        long orderId = submitResting("mkc-park");

        long requestId = requestCancel(orderId, "ops1");

        // The order is untouched — no cancel event was published, only a pending request.
        mvc.perform(get("/api/v1/orders/{id}", orderId).with(as("trader1", "TRADER")))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/api/v1/cancel-requests").with(as("ops2", "OPS")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id == " + requestId + ")].requestedBy").value("ops1"));
    }

    @Test
    void aDifferentOpsUserApprovesAndTheCancelExecutes() throws Exception {
        long orderId = submitResting("mkc-approve");
        long requestId = requestCancel(orderId, "ops1");

        mvc.perform(post("/api/v1/cancel-requests/{id}/approve", requestId).with(as("ops2", "OPS")))
                .andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> mvc.perform(
                        get("/api/v1/orders/{id}", orderId).with(as("trader1", "TRADER")))
                .andExpect(jsonPath("$.status").value("CANCELLED")));

        List<String> actions = jdbc.sql("select action from orders.audit where order_id = :o order by id")
                .param("o", orderId)
                .query(String.class)
                .list();
        assertThat(actions).contains("CANCEL_APPROVAL_REQUESTED", "CANCEL_APPROVED");
        String approver = jdbc.sql(
                        "select principal from orders.audit where order_id = :o and action = 'CANCEL_APPROVED'")
                .param("o", orderId)
                .query(String.class)
                .single();
        assertThat(approver).isEqualTo("ops2");
    }

    @Test
    void theRequesterCannotApproveTheirOwnRequest() throws Exception {
        long orderId = submitResting("mkc-self");
        long requestId = requestCancel(orderId, "ops1");

        mvc.perform(post("/api/v1/cancel-requests/{id}/approve", requestId).with(as("ops1", "OPS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSecondPendingRequestForTheSameOrderConflicts() throws Exception {
        long orderId = submitResting("mkc-dup");
        requestCancel(orderId, "ops1");

        mvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(as("ops2", "OPS")))
                .andExpect(status().isConflict());
    }

    @Test
    void decliningLeavesTheOrderWorking() throws Exception {
        long orderId = submitResting("mkc-decline");
        long requestId = requestCancel(orderId, "ops1");

        mvc.perform(post("/api/v1/cancel-requests/{id}/decline", requestId).with(as("ops1", "OPS")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orders/{id}", orderId).with(as("trader1", "TRADER")))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        String state = jdbc.sql("select status from orders.cancel_request where id = :i")
                .param("i", requestId)
                .query(String.class)
                .single();
        assertThat(state).isEqualTo("DECLINED");
    }

    @Test
    void opsRequestOnATerminalOrderIsAConflict() throws Exception {
        String body = mvc.perform(post("/api/v1/orders")
                        .with(as("trader1", "TRADER"))
                        .header("Idempotency-Key", "mkc-terminal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1000000}"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long orderId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(as("ops1", "OPS")))
                .andExpect(status().isConflict());
    }

    @Test
    void traderSelfCancelRemainsImmediate() throws Exception {
        long orderId = submitResting("mkc-selfcancel");

        mvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(as("trader1", "TRADER")))
                .andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> mvc.perform(
                        get("/api/v1/orders/{id}", orderId).with(as("trader1", "TRADER")))
                .andExpect(jsonPath("$.status").value("CANCELLED")));
    }

    @Test
    void adminMayObserveRequestsButNotDecide() throws Exception {
        long orderId = submitResting("mkc-admin");
        long requestId = requestCancel(orderId, "ops1");

        mvc.perform(get("/api/v1/cancel-requests").with(as("admin1", "ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/cancel-requests/{id}/approve", requestId).with(as("admin1", "ADMIN")))
                .andExpect(status().isForbidden());
    }
}
