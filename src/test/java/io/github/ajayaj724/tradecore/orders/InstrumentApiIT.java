package io.github.ajayaj724.tradecore.orders;

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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InstrumentApiIT {

    private final MockMvc mvc;

    @Autowired
    InstrumentApiIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void listsTradableInstrumentsAlphabetically() throws Exception {
        mvc.perform(get("/api/v1/instruments")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "trader1"))
                                .authorities(createAuthorityList("ROLE_TRADER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("ACME"))
                .andExpect(jsonPath("$[0].name").value("Acme Corp"))
                .andExpect(jsonPath("$[1].symbol").value("INFY"))
                .andExpect(jsonPath("$[1].name").value("Infosys Ltd"));
    }
}
