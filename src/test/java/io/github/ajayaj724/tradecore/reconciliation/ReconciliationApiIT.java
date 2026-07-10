package io.github.ajayaj724.tradecore.reconciliation;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReconciliationApiIT {

    private final MockMvc mvc;

    @Autowired
    ReconciliationApiIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor role(String username, String role) {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(createAuthorityList("ROLE_" + role));
    }

    @Test
    void adminSeesDriftAndPerAccountEquity() throws Exception {
        mvc.perform(get("/api/v1/reconciliation").with(role("admin1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driftPairs").isNumber())
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].account").value("trader1"))
                .andExpect(jsonPath("$.accounts[0].equity").isNumber())
                .andExpect(jsonPath("$.accounts[0].cashDrift").isNumber())
                .andExpect(jsonPath("$.accounts[1].account").value("trader2"));
    }

    @Test
    void reconciliationIsAdminOnly() throws Exception {
        mvc.perform(get("/api/v1/reconciliation").with(role("trader1", "TRADER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reconciliation").with(role("ops1", "OPS"))).andExpect(status().isForbidden());
    }
}
