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
class OrderHistoryIT {

    private final MockMvc mvc;

    @Autowired
    OrderHistoryIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String username) {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private void submit(String account, String idempotencyKey) throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader(account))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listsOnlyTheCallersOrders() throws Exception {
        submit("hist-owner", "hist-1");
        submit("hist-owner", "hist-2");
        submit("hist-other", "hist-3");

        mvc.perform(get("/api/v1/orders").with(trader("hist-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].account").value("hist-owner"))
                .andExpect(jsonPath("$[1].account").value("hist-owner"));
    }

    @Test
    void listsNewestOrderFirst() throws Exception {
        submit("hist-order", "hist-o1");
        submit("hist-order", "hist-o2");

        String body = mvc.perform(get("/api/v1/orders").with(trader("hist-order")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long first = ((Number) JsonPath.read(body, "$[0].id")).longValue();
        long second = ((Number) JsonPath.read(body, "$[1].id")).longValue();
        assertThat(first).isGreaterThan(second);
    }

    @Test
    void limitParamCapsTheResultSet() throws Exception {
        submit("hist-limit", "hist-l1");
        submit("hist-limit", "hist-l2");
        submit("hist-limit", "hist-l3");

        mvc.perform(get("/api/v1/orders").with(trader("hist-limit")).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
