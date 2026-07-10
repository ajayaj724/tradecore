package io.github.ajayaj724.tradecore.orders;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class OrderTypeEchoIT {

    private final MockMvc mvc;

    @Autowired
    OrderTypeEchoIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void omittedTypeIsEchoedAsLimit() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader("type-echo-a"))
                        .header("Idempotency-Key", "type-echo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LIMIT"));
    }

    @Test
    void marketTypeIsEchoedOnResponseAndHistory() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(trader("type-echo-b"))
                        .header("Idempotency-Key", "type-echo-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"ACME\",\"side\":\"BUY\",\"quantity\":1,\"type\":\"MARKET\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("MARKET"));

        mvc.perform(get("/api/v1/orders").with(trader("type-echo-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MARKET"));
    }
}
