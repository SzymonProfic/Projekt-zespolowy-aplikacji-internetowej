package pl.szymonprofic.foundlybackend.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JwtSecurityTests {
    static final String ISSUER = "http://localhost/realms/foundly";
    static RSAKey key;
    static HttpServer jwks;
    static JwtDecoder decoder;

    @BeforeAll static void setup() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("foundly-test").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/keys", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        jwks.start();
        decoder = new SecurityConfig().jwtDecoder(ISSUER, "http://127.0.0.1:" + jwks.getAddress().getPort() + "/keys",
                "foundly-backend", "foundly-frontend");
    }

    @AfterAll static void stop() { if (jwks != null) jwks.stop(0); }

    @Test void acceptsUserAccessTokenSignedByRealm() throws Exception {
        assertThat(decoder.decode(signed(claims().build(), key)).getSubject()).isEqualTo("alice");
    }
    @Test void rejectsExpiredToken() throws Exception {
        reject(claims().expirationTime(Date.from(Instant.now().minusSeconds(120))).build(), key);
    }
    @Test void rejectsTokenNotYetValid() throws Exception {
        reject(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(120))).build(), key);
    }
    @Test void rejectsWrongIssuer() throws Exception {
        reject(claims().issuer("http://other/realms/foundly").build(), key);
    }
    @Test void rejectsWrongAudience() throws Exception {
        reject(claims().audience("another-api").build(), key);
    }
    @Test void rejectsIdTokenAndServiceClient() throws Exception {
        reject(claims().claim("typ", "ID").build(), key);
        reject(claims().claim("azp", "service-client").build(), key);
    }
    @Test void rejectsMissingSubjectAndExpiry() throws Exception {
        reject(claims().subject(null).build(), key);
        reject(claims().issueTime(null).build(), key);
        reject(claims().expirationTime(null).build(), key);
    }
    @Test void rejectsWrongSignature() throws Exception {
        reject(claims().build(), new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate());
    }

    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("alice").audience("foundly-backend")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ", "Bearer").claim("azp", "foundly-frontend");
    }
    static String signed(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }
    static void reject(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        String token = signed(claims, signingKey);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
