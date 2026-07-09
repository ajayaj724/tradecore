package io.github.ajayaj724.tradecore.config;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SecurityConfigIT {

    private final MockMvc mvc;

    @Autowired
    SecurityConfigIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void prometheusRequiresAuthByDefault() throws Exception {
        // Secure by default: the scrape endpoint is only opened under the local profile (ADR-0017).
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void unauthenticatedGetsProblemJson401() throws Exception {
        mvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void authenticatedUnknownPathGets404NotAuthError() throws Exception {
        mvc.perform(get("/api/v1/anything").with(jwt().authorities(createAuthorityList("ROLE_TRADER"))))
                .andExpect(status().isNotFound());
    }
}
