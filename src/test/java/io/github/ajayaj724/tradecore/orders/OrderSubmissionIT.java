package io.github.ajayaj724.tradecore.orders;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OrderSubmissionIT {

    private final MockMvc mvc;

    @Autowired
    OrderSubmissionIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String username) {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void buyWithinCashIsAcceptedAndPublished() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "k-accept-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.filledQty").value(0));
    }

    @Test
    void buyOverCashIsRejectedButCreated() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "k-reject-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":100000000,\"quantity\":1000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("insufficient cash"));
    }

    @Test
    void duplicateIdempotencyKeyReturnsOriginal() throws Exception {
        String body = "{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1}";
        mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "k-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "k-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void unknownSymbolIs422ProblemJson() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader("trader1"))
                        .header("Idempotency-Key", "k-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"NOPE\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
