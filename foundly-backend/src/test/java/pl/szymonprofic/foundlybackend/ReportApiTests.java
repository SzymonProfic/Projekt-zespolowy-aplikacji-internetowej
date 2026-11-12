package pl.szymonprofic.foundlybackend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReportApiTests {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM report");
        jdbc.update("DELETE FROM app_user");
        when(decoder.decode("alice")).thenReturn(token("alice", "Alice"));
        when(decoder.decode("bob")).thenReturn(token("bob", "Bob"));
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid token"));
    }

    @Test
    void publicReadsAndProtectedOperations() throws Exception {
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        mvc.perform(get("/api/reports")).andExpect(status().isOk());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reports/mine")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @Test
    void openApiAndSwaggerArePublic() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/reports'].post").exists())
                .andExpect(jsonPath("$.paths['/api/reports/{id}/matches']").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.type").value("oauth2"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.authorizationUrl")
                        .value("http://localhost:8081/realms/foundly/protocol/openid-connect/auth"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.tokenUrl")
                        .value("http://localhost:8081/realms/foundly/protocol/openid-connect/token"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.scopes.openid").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/oauth2-redirect.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("foundly-frontend")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("usePkceWithAuthorizationCodeGrant")));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.oauth2RedirectUrl").value("http://localhost:8080/swagger-ui/oauth2-redirect.html"));
    }

    @Test
    void concurrentProfileProvisioningDoesNotDuplicateUsers() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> (java.util.concurrent.Callable<String>) () -> {
                        var response = mvc.perform(put("/api/me").header("Authorization", "Bearer alice"))
                                .andExpect(status().is2xxSuccessful()).andReturn().getResponse();
                        return json.readTree(response.getContentAsString()).path("id").asText();
                    }).toList();
            var ids = new HashSet<String>();
            for (var result : executor.invokeAll(tasks)) ids.add(result.get());
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void concurrentSynchronizationCannotReplaceNewerEmailWithOlderClaims() throws Exception {
        request(put("/api/me"), "alice", 201);
        when(decoder.decode("alice-new")).thenReturn(token("alice", "Alicja", "new@example.test", false,
                Instant.now().minusSeconds(20)));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (java.util.concurrent.Callable<JsonNode>) () ->
                            request(put("/api/me"), i % 2 == 0 ? "alice" : "alice-new", 200)).toList();
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        JsonNode current = request(get("/api/me"), "alice", 200);
        assertThat(current.path("email").asText()).isEqualTo("new@example.test");
        assertThat(current.path("emailVerified").asBoolean()).isFalse();
    }

    @Test
    void migrationPreservesExistingProfilesAndLeavesEmailForNextSynchronization() throws Exception {
        jdbc.execute("CREATE DATABASE profile_migration_check");
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/profile_migration_check");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .target("1").load().migrate();
            UUID id = UUID.randomUUID();
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 var insert = connection.prepareStatement("INSERT INTO app_user(id, auth_issuer, auth_subject, display_name, created_at) VALUES (?, ?, ?, ?, ?::timestamptz)")) {
                insert.setObject(1, id); insert.setString(2, "http://localhost:8081/realms/foundly");
                insert.setString(3, "existing"); insert.setString(4, "Existing user");
                insert.setString(5, "2026-01-01T12:00:00Z"); insert.executeUpdate();
            }
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT * FROM app_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("id", UUID.class)).isEqualTo(id);
                assertThat(result.getString("display_name")).isEqualTo("Existing user");
                assertThat(result.getString("email")).isNull();
                assertThat(result.getBoolean("email_verified")).isFalse();
                assertThat(result.getTimestamp("updated_at")).isEqualTo(result.getTimestamp("created_at"));
                assertThat(result.getTimestamp("identity_claims_issued_at")).isNull();
            }
        } finally {
            jdbc.execute("DROP DATABASE profile_migration_check");
        }
    }

    @Test
    void profileAndAuthorIdentityRemainStableWhenNameChanges() throws Exception {
        JsonNode first = request(put("/api/me"), "alice", 201);
        when(decoder.decode("alice")).thenReturn(token("alice", "Alicja", "alice@example.test", true,
                Instant.now().minusSeconds(30)));
        JsonNode updated = request(put("/api/me"), "alice", 200);
        assertThat(updated.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(updated.path("displayName").asText()).isEqualTo("Alicja");
        assertThat(updated.has("authSubject")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void getProfileIsReadOnlyAndPutIsIdempotent() throws Exception {
        request(get("/api/me"), "alice", 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
        JsonNode created = request(put("/api/me"), "alice", 201);
        assertThat(created.path("email").asText()).isEqualTo("alice@example.test");
        assertThat(created.path("emailVerified").asBoolean()).isTrue();
        JsonNode repeated = request(put("/api/me"), "alice", 200);
        assertThat(repeated).isEqualTo(created);
        assertThat(request(get("/api/me"), "alice", 200)).isEqualTo(created);
        assertThat(created.has("authIssuer")).isFalse();
        assertThat(created.has("identityClaimsIssuedAt")).isFalse();
        when(decoder.decode("alice")).thenReturn(token("alice", "Alicja", "new@example.test", false,
                Instant.now().minusSeconds(30)));
        // A read does not silently synchronize or replace email.
        assertThat(request(get("/api/me"), "alice", 200)).isEqualTo(created);
        JsonNode updated = request(put("/api/me"), "alice", 200);
        assertThat(updated.path("id")).isEqualTo(created.path("id"));
        assertThat(updated.path("createdAt")).isEqualTo(created.path("createdAt"));
        assertThat(updated.path("email").asText()).isEqualTo("new@example.test");
        assertThat(updated.path("emailVerified").asBoolean()).isFalse();
    }

    @Test
    void olderAndEquallyIssuedTokensCannotRevertEmailOrVerification() throws Exception {
        Instant newest = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        when(decoder.decode("alice")).thenReturn(token("alice", "Alice", "current@example.test", false, newest));
        JsonNode current = request(put("/api/me"), "alice", 201);
        when(decoder.decode("alice")).thenReturn(token("alice", "Old name", "old@example.test", true, newest.minusSeconds(10)));
        assertThat(request(put("/api/me"), "alice", 200)).isEqualTo(current);
        when(decoder.decode("alice")).thenReturn(token("alice", "Another name", "another@example.test", true, newest));
        assertThat(request(put("/api/me"), "alice", 200)).isEqualTo(current);
        when(decoder.decode("alice")).thenReturn(token("alice", "Alice", "current@example.test", true, newest.plusSeconds(1)));
        assertThat(request(put("/api/me"), "alice", 200).path("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void missingEmailDisablesVerificationAndMalformedEmailIsRejected() throws Exception {
        request(put("/api/me"), "alice", 201);
        when(decoder.decode("alice")).thenReturn(token("alice", "Alice", null, true, Instant.now().minusSeconds(30)));
        JsonNode noEmail = request(put("/api/me"), "alice", 200);
        assertThat(noEmail.path("email").isNull()).isTrue();
        assertThat(noEmail.path("emailVerified").asBoolean()).isFalse();
        when(decoder.decode("bob")).thenReturn(token("bob", "Bob", "not-an-email", true, Instant.now().minusSeconds(30)));
        request(put("/api/me"), "bob", 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void emailIsNeitherAnIdentityKeyNorAcceptedFromTheRequestBody() throws Exception {
        when(decoder.decode("alice")).thenReturn(token("alice", "Alice", "shared@example.test", true, Instant.now().minusSeconds(30)));
        when(decoder.decode("bob")).thenReturn(token("bob", "Bob", "shared@example.test", true, Instant.now().minusSeconds(30)));
        JsonNode first = request(put("/api/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"spoofed@example.test\",\"emailVerified\":false}"), "alice", 201);
        JsonNode second = request(put("/api/me"), "bob", 201);
        assertThat(first.path("email").asText()).isEqualTo("shared@example.test");
        assertThat(first.path("emailVerified").asBoolean()).isTrue();
        assertThat(first.path("id")).isNotEqualTo(second.path("id"));
    }

    @Test
    void emailIsNotExposedOnPublicOrOwnedReportResponses() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        assertThat(report.has("email")).isFalse();
        String publicReport = mvc.perform(get("/api/reports/" + report.path("id").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String publicList = mvc.perform(get("/api/reports")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(publicReport).doesNotContain("alice@example.test");
        assertThat(publicList).doesNotContain("alice@example.test");
    }

    @Test
    void publicDetailsExposeMapPointWithoutGrantingOwnership() throws Exception {
        JsonNode created = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        String path = "/api/reports/" + created.path("id").asText();
        for (String user : new String[]{null, "bob", "alice"}) {
            JsonNode details = request(get(path), user, 200);
            assertThat(details.path("locationPoint").path("latitude").asDouble()).isEqualTo(50.061);
            assertThat(details.path("locationPoint").path("longitude").asDouble()).isEqualTo(19.937);
            assertThat(details.path("owner").asBoolean()).isEqualTo("alice".equals(user));
            assertThat(details.path("editableLocation").isNull()).isEqualTo(!"alice".equals(user));
        }
    }

    @Test
    void reportLifecycleOwnershipAndOptimisticVersion() throws Exception {
        JsonNode created = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        String path = "/api/reports/" + created.path("id").asText();
        assertThat(created.path("owner").asBoolean()).isTrue();
        assertThat(created.path("category").asText()).isEqualTo("Klucze");
        assertThat(created.path("editableLocation").path("longitude").asDouble()).isEqualTo(19.937);
        assertThat(created.path("editableLocation").path("latitude").asDouble()).isEqualTo(50.061);
        JsonNode anonymous = request(get(path), null, 200);
        assertThat(anonymous.path("owner").asBoolean()).isFalse();
        assertThat(anonymous.path("editableLocation").isNull()).isTrue();
        request(patch(path + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"status\":\"RESOLVED\"}"), "bob", 403);
        request(delete(path).param("version", "0"), "bob", 403);
        var edit = Map.of("version", 0, "content", content(19.937, 50.061, "2026-01-01"));
        request(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)), "bob", 403);
        JsonNode edited = request(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)), "alice", 200);
        assertThat(edited.path("version").asLong()).isEqualTo(1);
        request(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)), "alice", 409);
        JsonNode resolved = request(patch(path + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"status\":\"RESOLVED\"}"), "alice", 200);
        assertThat(resolved.path("recovered").asBoolean()).isTrue();
        JsonNode repeated = request(patch(path + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"status\":\"RESOLVED\"}"), "alice", 200);
        assertThat(repeated.path("resolvedAt")).isEqualTo(resolved.path("resolvedAt"));
        assertThat(request(get("/api/reports"), null, 200).path("totalElements").asInt()).isZero();
        assertThat(request(get("/api/reports").param("status", "RESOLVED"), null, 200).path("totalElements").asInt()).isEqualTo(1);
        request(delete(path).param("version", "1"), "alice", 409);
        request(delete(path).param("version", resolved.path("version").asText()), "alice", 204);
        request(get(path), null, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(6);
    }

    @Test
    void filtersUseMetersAndLiteralSearchAndMineUsesToken() throws Exception {
        create("alice", "lost", 19.937, 50.061, "2026-01-01");
        create("bob", "found", 19.938, 50.061, "2026-01-02");
        create("bob", "found", 21.0, 52.0, "2026-01-03");
        JsonNode nearby = request(get("/api/reports").param("longitude", "19.937").param("latitude", "50.061")
                .param("radiusMeters", "1000"), null, 200);
        assertThat(nearby.path("totalElements").asInt()).isEqualTo(2);
        // The second point is about 72 meters away at this latitude.
        assertThat(request(get("/api/reports").param("longitude", "19.937").param("latitude", "50.061")
                .param("radiusMeters", "50"), null, 200).path("totalElements").asInt()).isEqualTo(1);
        assertThat(request(get("/api/reports").param("longitude", "19.937").param("latitude", "50.061")
                .param("radiusMeters", "100"), null, 200).path("totalElements").asInt()).isEqualTo(2);
        for (String radius : List.of("0", "100001")) {
            request(get("/api/reports").param("longitude", "19.937").param("latitude", "50.061")
                    .param("radiusMeters", radius), null, 400);
        }
        request(get("/api/reports").param("radiusMeters", "1000"), null, 400);
        assertThat(request(get("/api/reports/mine").param("status", "ALL"), "alice", 200).path("totalElements").asInt()).isEqualTo(1);
        assertThat(request(get("/api/reports").param("type", "found").param("categoryCode", "KEYS")
                .param("dateFrom", "2026-01-02").param("dateTo", "2026-01-02"), null, 200).path("totalElements").asInt()).isEqualTo(1);
        assertThat(request(get("/api/reports").param("q", "%"), null, 200).path("totalElements").asInt()).isZero();
        request(get("/api/reports").param("longitude", "19"), null, 400);
        request(get("/api/reports").param("size", "101"), null, 400);
        request(get("/api/reports").param("type", "invalid"), null, 400);
        request(get("/api/reports").param("dateFrom", "2026-02-01").param("dateTo", "2026-01-01"), null, 400);
    }

    @Test
    void paginationCountsAllFilteredRowsAndKeepsDefaultOrderStable() throws Exception {
        var expectedIds = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            expectedIds.add(create("alice", "lost", 19.937, 50.061, "2026-01-01").path("id").asText());
        }
        // Equal publication times must be resolved deterministically by UUID descending.
        jdbc.update("UPDATE report SET created_at = TIMESTAMPTZ '2026-01-02 12:00:00+00'");
        expectedIds.sort(Comparator.reverseOrder());
        var actualIds = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            JsonNode result = request(get("/api/reports").param("page", String.valueOf(page)).param("size", "2"), null, 200);
            assertThat(result.path("totalElements").asInt()).isEqualTo(4);
            assertThat(result.path("totalPages").asInt()).isEqualTo(2);
            assertThat(result.path("page").asInt()).isEqualTo(page);
            for (JsonNode report : result.path("content")) actualIds.add(report.path("id").asText());
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    @Test
    void spatialPaginationSortsByDistanceWithStableTiesAndAccurateCounts() throws Exception {
        JsonNode distant = create("alice", "lost", 19.943, 50.061, "2026-01-01");
        JsonNode nearby = create("bob", "found", 19.938, 50.061, "2026-01-01");
        JsonNode centerA = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        JsonNode centerB = create("bob", "found", 19.937, 50.061, "2026-01-01");
        create("bob", "found", 21.0, 52.0, "2026-01-01");
        var expectedIds = new ArrayList<>(List.of(centerA.path("id").asText(), centerB.path("id").asText()));
        expectedIds.sort(Comparator.naturalOrder());
        expectedIds.add(nearby.path("id").asText());
        expectedIds.add(distant.path("id").asText());
        var actualIds = new ArrayList<String>();
        for (int page = 0; page < 5; page++) {
            JsonNode result = request(get("/api/reports").param("longitude", "19.937").param("latitude", "50.061")
                    .param("radiusMeters", "1000").param("page", String.valueOf(page)).param("size", "1"), null, 200);
            assertThat(result.path("totalElements").asInt()).isEqualTo(4);
            assertThat(result.path("totalPages").asInt()).isEqualTo(4);
            for (JsonNode report : result.path("content")) actualIds.add(report.path("id").asText());
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        JsonNode mine = request(get("/api/reports/mine").param("longitude", "19.937").param("latitude", "50.061")
                .param("radiusMeters", "1000").param("size", "1"), "alice", 200);
        assertThat(mine.path("totalElements").asInt()).isEqualTo(2);
        assertThat(mine.path("content").get(0).path("id").asText()).isEqualTo(centerA.path("id").asText());
    }

    @Test
    void textSearchIgnoresCaseAndTreatsWildcardAndEscapeCharactersLiterally() throws Exception {
        var payload = new HashMap<>(content(19.937, 50.061, "2026-01-01"));
        payload.put("type", "lost");
        payload.put("title", "Żółte klucze 50%_!");
        request(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), "alice", 201);
        create("bob", "found", 19.938, 50.061, "2026-01-01");
        assertThat(request(get("/api/reports").param("q", "ŻÓŁTE"), null, 200).path("totalElements").asInt()).isEqualTo(1);
        assertThat(request(get("/api/reports").param("q", "%_!"), null, 200).path("totalElements").asInt()).isEqualTo(1);
        assertThat(request(get("/api/reports").param("q", "BReLoKiem"), null, 200).path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void invalidInputsAndUnknownFieldsAreRejected() throws Exception {
        var c = new HashMap<>(content(19.937, 50.061, "2026-01-01"));
        c.put("title", "  ");
        var payload = new HashMap<>(c); payload.put("type", "lost");
        request(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), "alice", 400);
        payload = new HashMap<>(content(19.937, 50.061, LocalDate.now().plusYears(1).toString())); payload.put("type", "lost");
        request(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), "alice", 400);
        payload = new HashMap<>(content(181.0, 50.061, "2026-01-01")); payload.put("type", "lost");
        request(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), "alice", 400);
        payload = new HashMap<>(content(19.937, 50.061, "2026-01-01")); payload.put("type", "lost"); payload.put("authorId", UUID.randomUUID().toString());
        request(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), "alice", 400);
    }

    private JsonNode create(String user, String type, double lon, double lat, String date) throws Exception {
        var payload = new HashMap<>(content(lon, lat, date)); payload.put("type", type);
        return request(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user, 201);
    }

    private Map<String, Object> content(double lon, double lat, String date) {
        return Map.of("title", "Klucze z brelokiem", "categoryCode", "KEYS", "description", "Znaleziono klucze z niebieskim brelokiem",
                "color", "Niebieski", "eventDate", date,
                "location", Map.of("label", "Kraków, Stare Miasto", "longitude", lon, "latitude", lat));
    }

    private JsonNode request(MockHttpServletRequestBuilder request, String user, int expected) throws Exception {
        if (user != null) request.header("Authorization", "Bearer " + user);
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? json.nullNode() : json.readTree(response);
    }

    private Jwt token(String subject, String name) {
        return token(subject, name, subject + "@example.test", true, Instant.now().minusSeconds(60));
    }

    private Jwt token(String subject, String name, String email, boolean verified, Instant issuedAt) {
        var builder = Jwt.withTokenValue(subject).header("alg", "RS256").issuer("http://localhost:8081/realms/foundly")
                .subject(subject).claim("preferred_username", name).claim("email_verified", verified)
                .issuedAt(issuedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).expiresAt(Instant.now().plusSeconds(300));
        if (email != null) builder.claim("email", email);
        return builder.build();
    }
}
