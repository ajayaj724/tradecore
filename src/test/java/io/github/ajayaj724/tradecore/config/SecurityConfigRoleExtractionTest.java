package io.github.ajayaj724.tradecore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigRoleExtractionTest {

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("trader1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void realmAccessRolesAreMappedToRolePrefixedAuthorities() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("TRADER", "OPS")))
                .build();

        Collection<GrantedAuthority> roles = SecurityConfig.extractRealmRoles(jwt);

        assertThat(roles)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_TRADER", "ROLE_OPS");
    }

    @Test
    void missingRealmAccessClaimYieldsNoAuthorities() {
        Jwt jwt = baseJwt().build();

        Collection<GrantedAuthority> roles = SecurityConfig.extractRealmRoles(jwt);

        assertThat(roles).isEmpty();
    }

    @Test
    void realmAccessWithNonListRolesYieldsNoAuthorities() {
        Jwt jwt = baseJwt().claim("realm_access", Map.of("roles", "TRADER")).build();

        Collection<GrantedAuthority> roles = SecurityConfig.extractRealmRoles(jwt);

        assertThat(roles).isEmpty();
    }

    @Test
    void nullRoleEntriesAreFilteredOutWithoutThrowing() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", Arrays.asList("TRADER", null)))
                .build();

        Collection<GrantedAuthority> roles = SecurityConfig.extractRealmRoles(jwt);

        assertThat(roles).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_TRADER");
    }
}
