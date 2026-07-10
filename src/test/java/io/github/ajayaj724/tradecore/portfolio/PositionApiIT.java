package io.github.ajayaj724.tradecore.portfolio;

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
class PositionApiIT {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    @Autowired
    PositionApiIT(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private static RequestPostProcessor trader(String u) {
        return jwt().jwt(j -> j.claim("preferred_username", u)).authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void listsTheCallersPositionsWithIntegerPnl() throws Exception {
        // 10 shares at total cost 100000 (avg 10000); marked at 11000 → unrealized 10*11000-100000.
        jdbc.sql("insert into portfolio.position (account, symbol, total_qty, total_cost, realized_pnl)"
                        + " values ('pos-user', 'POS-A', 10, 100000, 2500)")
                .update();
        jdbc.sql("insert into portfolio.mark_price (symbol, price) values ('POS-A', 11000)"
                        + " on conflict (symbol) do update set price = 11000")
                .update();

        mvc.perform(get("/api/v1/positions").with(trader("pos-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("POS-A"))
                .andExpect(jsonPath("$[0].quantity").value(10))
                .andExpect(jsonPath("$[0].totalCost").value(100000))
                .andExpect(jsonPath("$[0].markPrice").value(11000))
                .andExpect(jsonPath("$[0].realizedPnl").value(2500))
                .andExpect(jsonPath("$[0].unrealizedPnl").value(10000));
    }

    @Test
    void unmarkedSymbolReportsNoMarkAndZeroUnrealized() throws Exception {
        jdbc.sql("insert into portfolio.position (account, symbol, total_qty, total_cost, realized_pnl)"
                        + " values ('pos-unmarked', 'POS-B', 5, 50000, 0)")
                .update();

        mvc.perform(get("/api/v1/positions").with(trader("pos-unmarked")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].markPrice").doesNotExist())
                .andExpect(jsonPath("$[0].unrealizedPnl").value(0));
    }

    @Test
    void emptyBookIsAnEmptyList() throws Exception {
        mvc.perform(get("/api/v1/positions").with(trader("pos-nobody")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
