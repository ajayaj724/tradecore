package io.github.ajayaj724.tradecore.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the real OAuth2 resource server filter chain (unlike SecurityConfigIT's mock {@code
 * jwt()} post-processor) so SecurityConfig's realm_access -&gt; ROLE_* converter actually runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, KeycloakRoleMappingIT.StubJwtDecoderConfig.class})
class KeycloakRoleMappingIT {

    private final MockMvc mvc;

    @Autowired
    KeycloakRoleMappingIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void realmAccessRolesAreConvertedAndRequestAuthenticates() throws Exception {
        mvc.perform(get("/api/v1/anything").header("Authorization", "Bearer with-roles"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingRealmAccessClaimStillAuthenticatesWithNoAuthorities() throws Exception {
        mvc.perform(get("/api/v1/anything").header("Authorization", "Bearer without-roles"))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubJwtDecoderConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                Jwt.Builder builder = Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject("trader1")
                        .issuedAt(Instant.EPOCH)
                        .expiresAt(Instant.EPOCH.plusSeconds(60));
                if ("with-roles".equals(token)) {
                    builder.claim("realm_access", Map.of("roles", List.of("TRADER")));
                }
                return builder.build();
            };
        }
    }
}
