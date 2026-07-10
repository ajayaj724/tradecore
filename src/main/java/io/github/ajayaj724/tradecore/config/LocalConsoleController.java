package io.github.ajayaj724.tradecore.config;

import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Local dev console: serves the launcher page and mints a demo bearer token server-side, so the
 * in-browser action buttons can call the same-origin API with no secret in the page and no
 * cross-origin call to Keycloak. {@code @Profile("local")} — this never loads outside local
 * development, where a token-minting endpoint would be an obvious hole.
 */
@RestController
@Profile("local")
class LocalConsoleController {

    private final String tokenUrl;
    private final String clientId;
    private final RestClient http = RestClient.create();

    LocalConsoleController(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${tradecore.security.local-console-client:tradecore-api}") String clientId) {
        this.tokenUrl = issuer + "/protocol/openid-connect/token";
        this.clientId = clientId;
    }

    @GetMapping(value = "/console.html", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<byte[]> console() throws IOException {
        try (var in = new ClassPathResource("local-console/console.html").getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(in.readAllBytes());
        }
    }

    /** Demo-user password grant, server-side. Local only; password is the fixed demo secret. */
    @GetMapping("/local/token/{user}")
    Map<String, Object> token(@PathVariable String user) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", user);
        form.add("password", "demo");
        Map<?, ?> body = http.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        Object token = body == null ? null : body.get("access_token");
        return Map.of("access_token", token == null ? "" : token);
    }
}
