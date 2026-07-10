package io.github.ajayaj724.tradecore.risk;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BalanceApiIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    BalanceApiIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String username) {
        return jwt().jwt(j -> j.claim("preferred_username", username)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void returnsSettledHeldAndAvailableForTheCaller() throws Exception {
        jdbc.sql("insert into risk.settled_cash (account, amount) values ('bal-user', 500000)")
                .update();
        jdbc.sql("insert into risk.cash_hold (order_id, account, unit_price, remaining_qty)"
                        + " values (990001, 'bal-user', 1000, 100)")
                .update();

        mvc.perform(get("/api/v1/balances").with(trader("bal-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("bal-user"))
                .andExpect(jsonPath("$.settled").value(500000))
                .andExpect(jsonPath("$.held").value(100000))
                .andExpect(jsonPath("$.available").value(400000));
    }

    @Test
    void reportsZeroBalancesForAnAccountWithNoLedgerHistory() throws Exception {
        mvc.perform(get("/api/v1/balances").with(trader("bal-nobody")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settled").value(0))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.available").value(0));
    }
}
