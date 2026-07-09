package io.github.ajayaj724.tradecore.orders;

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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Fresh engine per class so the resting order this test leaves behind cannot leak into the suite.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CancelValidationIT {

    private final MockMvc mvc;

    @Autowired
    CancelValidationIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    private long submit(String user, String body, String key) throws Exception {
        String resp = mvc.perform(post("/api/v1/orders")
                        .with(trader(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(resp, "$.id")).longValue();
    }

    @Test
    void cancellingAnotherTradersOrderIsNotFound() throws Exception {
        long id = submit("trader2", "{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":5000,\"quantity\":1}", "cxl-404");

        // trader1 must not even learn the order exists.
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(trader("trader1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingATerminalOrderIsAConflict() throws Exception {
        // A far-over-cash BUY is rejected by risk → terminal REJECTED, which is not cancellable.
        long id = submit(
                "trader1", "{\"symbol\":\"ACME\",\"side\":\"BUY\",\"price\":10000,\"quantity\":1000000}", "cxl-409");
        mvc.perform(get("/api/v1/orders/{id}", id).with(trader("trader1")))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(trader("trader1")))
                .andExpect(status().isConflict());
    }
}
