package io.github.ajayaj724.tradecore.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata and the Bearer-JWT security scheme, so Swagger UI shows an "Authorize"
 * control that sends the Keycloak access token (see {@code scripts/token.sh}) on secured calls.
 */
@Configuration
class OpenApiConfig {

    private static final String BEARER_JWT = "bearer-jwt";

    @Bean
    OpenAPI tradecoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("tradecore OMS API")
                        .version("v1")
                        .description("Enterprise brokerage order-management API. Money is in minor units "
                                + "(paise) end-to-end. Authenticate with a Keycloak JWT — see scripts/token.sh."))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_JWT,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
    }
}
