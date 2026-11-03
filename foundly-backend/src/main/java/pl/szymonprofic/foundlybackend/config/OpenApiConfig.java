package pl.szymonprofic.foundlybackend.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI foundlyOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        String oidc = issuer.replaceAll("/+$", "") + "/protocol/openid-connect";
        return new OpenAPI().info(new Info().title("Foundly REST API").version("v1")
                .description("Zgłoszenia zagubienia i znalezienia. Chronione operacje wymagają access tokenu Keycloak."))
                .components(new Components().addSecuritySchemes("keycloak", new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Logowanie użytkownika Keycloak przez Authorization Code z PKCE S256")
                        .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                .authorizationUrl(oidc + "/auth")
                                .tokenUrl(oidc + "/token")
                                .scopes(new Scopes().addString("openid", "Tożsamość użytkownika")
                                        .addString("profile", "Nazwa użytkownika").addString("email", "Adres e-mail i stan weryfikacji"))))));
    }

    @Bean
    OpenApiCustomizer operationSecurity() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            boolean publicRead = method == PathItem.HttpMethod.GET && (path.equals("/api/categories")
                    || path.equals("/api/reports") || path.equals("/api/reports/{id}"));
            operation.setSecurity(publicRead ? List.of() : List.of(new SecurityRequirement().addList("keycloak", List.of("openid", "profile", "email"))));
        }));
    }
}
