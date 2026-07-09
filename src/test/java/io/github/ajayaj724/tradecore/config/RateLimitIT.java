package io.github.ajayaj724.tradecore.config;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A tiny capacity (its own context via the property override) makes the per-user token bucket easy
 * to exhaust: the third call from the same principal is throttled with a 429 Problem Detail, while a
 * different principal keeps its own full bucket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = "tradecore.ratelimit.capacity=2")
class RateLimitIT {

    private final MockMvc mvc;

    @Autowired
    RateLimitIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static RequestPostProcessor trader(String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("preferred_username", subject))
                .authorities(createAuthorityList("ROLE_TRADER"));
    }

    @Test
    void perUserBurstBeyondCapacityIsThrottled() throws Exception {
        RequestPostProcessor user = trader("burst-tester");

        // capacity=2: the first two pass the limiter (404 — no such order), the third is throttled
        mvc.perform(get("/api/v1/orders/1").with(user)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/1").with(user)).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/orders/1").with(user))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void limitIsPerUserSoAFreshPrincipalPasses() throws Exception {
        // a different principal has its own full bucket; not throttled by another user's burst
        mvc.perform(get("/api/v1/orders/1").with(trader("solo-tester"))).andExpect(status().isNotFound());
    }
}
