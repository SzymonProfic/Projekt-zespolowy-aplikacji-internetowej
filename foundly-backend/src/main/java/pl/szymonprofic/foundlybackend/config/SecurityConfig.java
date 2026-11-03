package pl.szymonprofic.foundlybackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.time.*;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean
    Clock clock() { return Clock.system(ZoneId.of("Europe/Warsaw")); }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
                          @Value("${foundly.security.audience}") String audience,
                          @Value("${foundly.security.frontend-client}") String client) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        var claimConverter = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(claims -> {
            var converted = claimConverter.convert(claims);
            // Spring otherwise synthesizes iat from exp; profile ordering requires the actual claim.
            if (claims.get("iat") == null) converted.remove("iat");
            return converted;
        });
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), userTokenValidator(audience, client)));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> userTokenValidator(String audience, String client) {
        return jwt -> {
            boolean valid = jwt.getAudience().contains(audience)
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 255
                    && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && "Bearer".equals(jwt.getClaimAsString("typ"))
                    && client.equals(jwt.getClaimAsString("azp"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Expected a user access token for Foundly API", null));
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories", "/api/reports").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/reports/mine").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/reports/*").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, exception) -> {
                            new org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint()
                                    .commence(request, response, exception);
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/problem+json");
                            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Wymagany poprawny access token Keycloak\"}");
                        }))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${foundly.cors.origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Location"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
