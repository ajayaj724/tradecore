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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// The local profile opens /actuator/prometheus for the compose scraper (ADR-0017); by default it is
// authenticated like every other endpoint.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("local")
class SecurityConfigPrometheusIT {

    private final MockMvc mvc;

    @Autowired
    SecurityConfigPrometheusIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void prometheusScrapeIsPublicUnderLocalProfile() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void authenticatedUnknownPathIs404ProblemJson() throws Exception {
        mvc.perform(get("/api/v1/missing").with(jwt().authorities(createAuthorityList("ROLE_TRADER"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
