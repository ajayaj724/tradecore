package io.github.ajayaj724.tradecore.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ProblemDetailsAuthHandlers handlers,
            @Value("${tradecore.security.expose-prometheus-scrape:false}") boolean exposePrometheusScrape,
            @Value("${tradecore.security.expose-local-console:false}") boolean exposeLocalConsole)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**", "/actuator/health")
                            .permitAll();
                    if (exposePrometheusScrape) {
                        // secured by default; opened only under the local profile for the compose scraper (ADR-0017)
                        auth.requestMatchers("/actuator/prometheus").permitAll();
                    }
                    if (exposeLocalConsole) {
                        // local dev console + its demo-token minter — local profile only (ADR-0026)
                        auth.requestMatchers("/console.html", "/local/**").permitAll();
                    }
                    // OpenAPI docs + Swagger UI are a sanctioned unauthenticated exception
                    auth.requestMatchers(
                                    "/v3/api-docs",
                                    "/v3/api-docs/**",
                                    "/v3/api-docs.yaml",
                                    "/swagger-ui/**",
                                    "/swagger-ui.html")
                            .permitAll()
                            .anyRequest()
                            .authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles()))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .build();
    }

    private static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = extractRealmRoles(jwt);
            return new JwtAuthenticationToken(jwt, authorities);
        };
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
