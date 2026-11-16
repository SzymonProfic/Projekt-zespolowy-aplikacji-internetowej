package pl.szymonprofic.foundlybackend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import pl.szymonprofic.foundlybackend.models.ReportEmbedding;
import pl.szymonprofic.foundlybackend.models.ClaimedMatchingJob;
import pl.szymonprofic.foundlybackend.repositories.ReportEmbeddingRepository;
import pl.szymonprofic.foundlybackend.dto.CreateReportRequest;
import pl.szymonprofic.foundlybackend.dto.ReportLocation;
import pl.szymonprofic.foundlybackend.dto.ReportContent;
import pl.szymonprofic.foundlybackend.dto.UpdateReportRequest;
import pl.szymonprofic.foundlybackend.services.CurrentUserService;
import pl.szymonprofic.foundlybackend.services.MatchingJobService;
import pl.szymonprofic.foundlybackend.services.ReportService;
import pl.szymonprofic.foundlybackend.services.EmbeddingClient;
import pl.szymonprofic.foundlybackend.services.EmbeddingClientException;
import pl.szymonprofic.foundlybackend.services.MatchingWorker;
import pl.szymonprofic.foundlybackend.repositories.ReportMatchRepository;
import pl.szymonprofic.foundlybackend.services.MatchingRankingService;
import pl.szymonprofic.foundlybackend.models.ReportMatchStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"foundly.matching.enabled=false", "foundly.matching.lease-duration=2m",
        "foundly.matching.max-attempts=5", "foundly.matching.retry-delay=5s"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReportApiTests {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
    static {
        postgres.setImage(new ImageFromDockerfile()
                .withDockerfile(Path.of("..", "docker", "postgres", "Dockerfile"))
                .withBuildImageCmdModifier(cmd -> cmd.withPlatform("linux/amd64")));
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportEmbeddingRepository embeddings;
    @Autowired MatchingJobService matchingJobs;
    @Autowired MatchingWorker matchingWorker;
    @Autowired ReportService reportService;
    @Autowired CurrentUserService currentUsers;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean EmbeddingClient embeddingClient;
    @MockitoSpyBean ReportMatchRepository storedMatches;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM report");
        jdbc.update("DELETE FROM app_user");
        when(decoder.decode("alice")).thenReturn(token("alice", "Alice"));
        when(decoder.decode("bob")).thenReturn(token("bob", "Bob"));
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid token"));
        when(embeddingClient.modelInfo()).thenReturn(new EmbeddingClient.ModelInfo(
                "sdadas/mmlw-roberta-base", "a".repeat(40), 768));
    }

    @Test
    void spatialAndVectorExtensionsAreEnabled() {
        assertThat(jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('postgis', 'vector')", String.class))
                .containsExactlyInAnyOrder("postgis", "vector");
        assertThat(jdbc.queryForObject(
                "SELECT '[1,0,0]'::vector <=> '[0,1,0]'::vector", Double.class))
                .isEqualTo(1.0);
    }

    @Test
    void reportEmbeddingStoresVectorAndMetadataAndIsDeletedWithReport() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        insertTestEmbedding(reportId, testEmbeddingVector());

        var stored = jdbc.queryForMap("""
                SELECT vector_dims(embedding) AS dimensions, model_id, model_revision,
                       preprocessing_version, input_hash, updated_at
                FROM report_embedding WHERE report_id = ?
                """, reportId);
        assertThat(stored.get("dimensions")).isEqualTo(768);
        assertThat(stored.get("model_id")).isEqualTo("sdadas/mmlw-roberta-base");
        assertThat(stored.get("model_revision")).isEqualTo("test-revision");
        assertThat(stored.get("preprocessing_version")).isEqualTo("v1");
        assertThat(stored.get("input_hash")).isEqualTo("a".repeat(64));
        assertThat(((java.sql.Timestamp) stored.get("updated_at")).toInstant())
                .isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));

        request(delete("/api/reports/" + reportId)
                .param("version", report.path("version").asText()), "alice", 204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_embedding WHERE report_id = ?",
                Integer.class, reportId)).isZero();
    }

    @Test
    void reportEmbeddingRequiresExistingReportCorrectDimensionsAndUniqueReport() throws Exception {
        JsonNode report = create("alice", "found", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertTestEmbedding(UUID.randomUUID(), testEmbeddingVector()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertTestEmbedding(reportId, "[1,0,0]"));

        insertTestEmbedding(reportId, testEmbeddingVector());
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertTestEmbedding(reportId, testEmbeddingVector()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_embedding WHERE report_id = ?",
                Integer.class, reportId)).isEqualTo(1);
    }

    @Test
    void embeddingRepositoryReturnsEmptyForReportWithoutEmbedding() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());

        assertThat(embeddings.findByReportId(reportId)).isEmpty();
        assertThat(embeddings.findByReportId(UUID.randomUUID())).isEmpty();
        assertThat(embeddings.isCurrent(reportId, "a".repeat(64), "sdadas/mmlw-roberta-base",
                "a".repeat(40), "v1")).isFalse();
    }

    @Test
    void embeddingRepositoryStoresVectorAndMetadataWithoutChangingReportVersion() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        var values = Collections.nCopies(768, 1.0 / Math.sqrt(768));
        var expected = new ReportEmbedding(reportId, values, "sdadas/mmlw-roberta-base", "a".repeat(40),
                "v1", "a".repeat(64), Instant.parse("2026-01-01T12:00:00.123456Z"));

        embeddings.save(expected);

        var stored = embeddings.findByReportId(reportId).orElseThrow();
        assertThat(stored).usingRecursiveComparison().ignoringFields("numbers").isEqualTo(expected);
        assertThat(stored.numbers()).hasSize(768);
        // pgvector stores single-precision floats, so a double round trip is approximate.
        assertThat(stored.numbers()).allSatisfy(value ->
                assertThat(value).isCloseTo(values.getFirst(), within(1e-8)));
        assertThat(jdbc.queryForObject("SELECT version FROM report WHERE id = ?", Long.class, reportId))
                .isEqualTo(report.path("version").asLong());
        assertThat(embeddings.isCurrent(reportId, expected.inputHash(), expected.modelId(),
                expected.modelRevision(), expected.preprocessingVersion())).isTrue();
        assertThat(embeddings.isCurrent(reportId, "b".repeat(64), expected.modelId(),
                expected.modelRevision(), expected.preprocessingVersion())).isFalse();
        assertThat(embeddings.isCurrent(reportId, expected.inputHash(), "different/model",
                expected.modelRevision(), expected.preprocessingVersion())).isFalse();
        assertThat(embeddings.isCurrent(reportId, expected.inputHash(), expected.modelId(),
                "b".repeat(40), expected.preprocessingVersion())).isFalse();
        assertThat(embeddings.isCurrent(reportId, expected.inputHash(), expected.modelId(),
                expected.modelRevision(), "v2")).isFalse();

        request(delete("/api/reports/" + reportId).param("version", report.path("version").asText()), "alice", 204);
        assertThat(embeddings.findByReportId(reportId)).isEmpty();
    }

    @Test
    void embeddingRepositoryUpdatesOneRowIncludingAllMetadata() throws Exception {
        JsonNode report = create("alice", "found", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        var vector = new ArrayList<>(Collections.nCopies(768, 0.0));
        vector.set(0, 1.0);
        var original = new ReportEmbedding(reportId, vector, "sdadas/mmlw-roberta-base", "a".repeat(40),
                "v1", "a".repeat(64), Instant.parse("2026-01-01T12:00:00Z"));
        embeddings.save(original);

        vector.set(0, -1.0);
        var replacement = new ReportEmbedding(reportId, vector, "different/model", "b".repeat(40),
                "v2", "b".repeat(64), Instant.parse("2026-01-02T12:00:00Z"));
        embeddings.save(replacement);
        embeddings.save(replacement);

        assertThat(embeddings.findByReportId(reportId)).contains(replacement);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_embedding WHERE report_id = ?",
                Integer.class, reportId)).isEqualTo(1);
        assertThat(embeddings.isCurrent(reportId, original.inputHash(), original.modelId(),
                original.modelRevision(), original.preprocessingVersion())).isFalse();
        assertThat(embeddings.isCurrent(reportId, replacement.inputHash(), replacement.modelId(),
                replacement.modelRevision(), replacement.preprocessingVersion())).isTrue();
    }

    @Test
    void creatingLostAndFoundReportsEnqueuesPendingJobsAndDeletingReportRemovesItsJob() throws Exception {
        for (String type : List.of("lost", "found")) {
            JsonNode report = create("alice", type, 19.937, 50.061, "2026-01-01");
            UUID reportId = UUID.fromString(report.path("id").asText());
            var job = jdbc.queryForMap("SELECT * FROM matching_job WHERE report_id = ?", reportId);
            assertThat(job.get("report_version")).isEqualTo(report.path("version").asLong());
            assertThat(job.get("pipeline_version")).isEqualTo("v2");
            assertThat(job.get("status")).isEqualTo("PENDING");
            assertThat(job.get("attempts")).isEqualTo(0);
            assertThat(job.get("available_at")).isEqualTo(job.get("created_at"));
            assertThat(job.get("updated_at")).isEqualTo(job.get("created_at"));
            assertThat(job.get("locked_until")).isNull();
            assertThat(job.get("lock_token")).isNull();
            assertThat(job.get("last_error")).isNull();
            assertThat(embeddings.findByReportId(reportId)).isEmpty();

            request(delete("/api/reports/" + reportId).param("version", report.path("version").asText()), "alice", 204);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE report_id = ?",
                    Integer.class, reportId)).isZero();
        }
    }

    @Test
    void schedulingDuplicateJobPreservesItsIdAndRetryState() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        jdbc.update("""
                UPDATE matching_job SET attempts = 3, available_at = available_at + interval '1 hour',
                    last_error = 'temporary failure' WHERE report_id = ?
                """, reportId);
        var before = jdbc.queryForMap("SELECT * FROM matching_job WHERE report_id = ?", reportId);

        Boolean inserted = new TransactionTemplate(transactionManager).execute(tx ->
                matchingJobs.schedule(reportId, report.path("version").asLong()));

        assertThat(inserted).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM matching_job WHERE report_id = ?", reportId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE report_id = ?",
                Integer.class, reportId)).isEqualTo(1);
    }

    @Test
    void concurrentSchedulingCreatesOnlyOneJob() throws Exception {
        JsonNode report = create("alice", "found", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        jdbc.update("DELETE FROM matching_job WHERE report_id = ?", reportId);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> (java.util.concurrent.Callable<Boolean>) () ->
                            new TransactionTemplate(transactionManager).execute(tx ->
                                    matchingJobs.schedule(reportId, report.path("version").asLong())))
                    .toList();
            int inserted = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (Boolean.TRUE.equals(result.get())) inserted++;
            }
            assertThat(inserted).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE report_id = ?",
                Integer.class, reportId)).isEqualTo(1);
    }

    @Test
    void reportAndJobAreBothRolledBackWhenTheTransactionFails() {
        Jwt jwt = token("alice", "Alice");
        var author = currentUsers.require(jwt);
        var request = new CreateReportRequest("lost", "Czarny portfel", "WALLETS_DOCUMENTS",
                "Skórzany portfel bez logo.", "czarny", LocalDate.of(2026, 1, 1),
                new ReportLocation("Kraków", 19.937, 50.061));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var report = reportService.create(request, author, jwt);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE report_id = ?",
                    Integer.class, report.id())).isEqualTo(1);
            throw new IllegalStateException("Simulated failure after both writes");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated failure after both writes");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM report", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job", Integer.class)).isZero();
    }

    @Test
    void schedulingRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> matchingJobs.schedule(UUID.randomUUID(), 0))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void claimingJobRecordsLeaseAndDoesNotReturnItAgainWhileLeaseIsActive() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        Instant before = Instant.now();

        var job = matchingJobs.claimNext().orElseThrow();

        assertThat(job.reportId()).isEqualTo(UUID.fromString(report.path("id").asText()));
        assertThat(job.reportVersion()).isEqualTo(report.path("version").asLong());
        assertThat(job.pipelineVersion()).isEqualTo("v2");
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.lockToken()).isNotNull();
        assertThat(job.lockedUntil()).isAfter(before.plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE id = ?", String.class, job.id()))
                .isEqualTo("PROCESSING");
        assertThat(matchingJobs.claimNext()).isEmpty();
    }

    @Test
    void claimingSkipsFutureJobsAndUnsupportedPipelineVersions() throws Exception {
        JsonNode future = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID futureId = UUID.fromString(future.path("id").asText());
        jdbc.update("UPDATE matching_job SET available_at = CURRENT_TIMESTAMP + interval '1 hour' WHERE report_id = ?",
                futureId);
        JsonNode unsupported = create("bob", "found", 19.937, 50.061, "2026-01-01");
        jdbc.update("UPDATE matching_job SET pipeline_version = 'future-v99' WHERE report_id = ?",
                UUID.fromString(unsupported.path("id").asText()));

        assertThat(matchingJobs.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM matching_job", Long.class)).isZero();
    }

    @Test
    void concurrentClaimingGivesTheJobToOnlyOneCaller() throws Exception {
        create("alice", "found", 19.937, 50.061, "2026-01-01");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> (java.util.concurrent.Callable<Optional<ClaimedMatchingJob>>) matchingJobs::claimNext)
                    .toList();
            int claimed = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (result.get().isPresent()) claimed++;
            }
            assertThat(claimed).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM matching_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void claimingSkipsLockedRowsAndCommitsIndependentlyOfTheCallerTransaction() throws Exception {
        JsonNode blocked = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID blockedId = UUID.fromString(blocked.path("id").asText());
        JsonNode available = create("bob", "found", 19.937, 50.061, "2026-01-01");

        var claimed = new TransactionTemplate(transactionManager).execute(tx -> {
            jdbc.queryForList("SELECT id FROM matching_job WHERE report_id = ? FOR UPDATE", blockedId);
            var job = matchingJobs.claimNext().orElseThrow();
            tx.setRollbackOnly();
            return job;
        });

        assertThat(claimed).isNotNull();
        assertThat(claimed.reportId()).isEqualTo(UUID.fromString(available.path("id").asText()));
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE id = ?", String.class, claimed.id()))
                .isEqualTo("PROCESSING");
        assertThat(matchingJobs.claimNext().orElseThrow().reportId()).isEqualTo(blockedId);
    }

    @Test
    void expiredLeaseIsReclaimedWithNewTokenAndOnlyItsOwnerCanComplete() throws Exception {
        create("alice", "lost", 19.937, 50.061, "2026-01-01");
        var oldLease = matchingJobs.claimNext().orElseThrow();
        jdbc.update("UPDATE matching_job SET locked_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                oldLease.id());
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(tx -> matchingJobs.complete(oldLease))).isFalse();

        var currentLease = matchingJobs.claimNext().orElseThrow();
        assertThat(currentLease.id()).isEqualTo(oldLease.id());
        assertThat(currentLease.attempts()).isEqualTo(2);
        assertThat(currentLease.lockToken()).isNotEqualTo(oldLease.lockToken());
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(tx -> matchingJobs.complete(oldLease))).isFalse();
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(tx -> matchingJobs.complete(currentLease))).isTrue();
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(tx -> matchingJobs.complete(currentLease))).isFalse();

        var stored = jdbc.queryForMap("SELECT status, lock_token, locked_until FROM matching_job WHERE id = ?", oldLease.id());
        assertThat(stored.get("status")).isEqualTo("COMPLETED");
        assertThat(stored.get("lock_token")).isNull();
        assertThat(stored.get("locked_until")).isNull();
        assertThat(matchingJobs.claimNext()).isEmpty();
    }

    @Test
    void jobCompletionParticipatesInTheResultTransaction() throws Exception {
        create("alice", "lost", 19.937, 50.061, "2026-01-01");
        var job = matchingJobs.claimNext().orElseThrow();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(matchingJobs.complete(job)).isTrue();
            throw new IllegalStateException("Simulated result write failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated result write failure");

        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE id = ?", String.class, job.id()))
                .isEqualTo("PROCESSING");
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(tx -> matchingJobs.complete(job))).isTrue();
    }

    @Test
    void workerGeneratesAndStoresEmbeddingsOutsideTheHttpTransactionForBothReportTypes() throws Exception {
        when(embeddingClient.generate(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return testEmbeddingResult();
        });
        for (String type : List.of("lost", "found")) {
            JsonNode report = create("alice", type, 19.937, 50.061, "2026-01-01");
            UUID reportId = UUID.fromString(report.path("id").asText());
            assertThat(embeddings.findByReportId(reportId)).isEmpty();

            assertThat(matchingWorker.processNext()).isTrue();

            var stored = embeddings.findByReportId(reportId).orElseThrow();
            assertThat(stored.numbers()).isEqualTo(testEmbeddingResult().numbers());
            assertThat(stored.modelId()).isEqualTo(testEmbeddingResult().modelId());
            assertThat(stored.modelRevision()).isEqualTo(testEmbeddingResult().modelRevision());
            assertThat(stored.preprocessingVersion()).isEqualTo("v1");
            assertThat(stored.inputHash()).matches("[0-9a-f]{64}");
            assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, reportId))
                    .isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT version FROM report WHERE id = ?", Long.class, reportId))
                    .isEqualTo(report.path("version").asLong());
            String prefix = type.equals("lost") ? "zapytanie: " : "";
            verify(embeddingClient).generate(prefix + "Klucze z brelokiem\nZnaleziono klucze z niebieskim brelokiem\nKolor: Niebieski");
        }
        assertThat(matchingWorker.processNext()).isFalse();
    }

    @Test
    void workerRetriesTransientFailuresAfterDelayAndThenCompletes() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        when(embeddingClient.generate(anyString()))
                .thenThrow(new EmbeddingClientException("timeout", true)).thenReturn(testEmbeddingResult());

        assertThat(matchingWorker.processNext()).isTrue();
        var failed = jdbc.queryForMap("SELECT * FROM matching_job WHERE report_id = ?", reportId);
        assertThat(failed.get("status")).isEqualTo("PENDING");
        assertThat(failed.get("attempts")).isEqualTo(1);
        assertThat(failed.get("lock_token")).isNull();
        assertThat(((java.sql.Timestamp) failed.get("available_at")).toInstant()).isAfter(Instant.now());
        assertThat(embeddings.findByReportId(reportId)).isEmpty();
        assertThat(matchingWorker.processNext()).isFalse();

        jdbc.update("UPDATE matching_job SET available_at = CURRENT_TIMESTAMP - interval '1 second' WHERE report_id = ?", reportId);
        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(reportId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, reportId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM matching_job WHERE report_id = ?", Integer.class, reportId))
                .isEqualTo(2);
        verify(embeddingClient, times(2)).generate(anyString());
    }

    @Test
    void workerMarksPermanentFailuresAndExhaustedRetriesDead() throws Exception {
        JsonNode permanent = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID permanentId = UUID.fromString(permanent.path("id").asText());
        when(embeddingClient.generate(anyString())).thenThrow(new EmbeddingClientException("wrong model", false));
        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, permanentId))
                .isEqualTo("DEAD");
        assertThat(embeddings.findByReportId(permanentId)).isEmpty();

        JsonNode exhausted = create("bob", "found", 19.937, 50.061, "2026-01-01");
        UUID exhaustedId = UUID.fromString(exhausted.path("id").asText());
        jdbc.update("UPDATE matching_job SET attempts = 4 WHERE report_id = ?", exhaustedId);
        doThrow(new EmbeddingClientException("timeout", true)).when(embeddingClient).generate(anyString());
        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, exhaustedId))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT attempts FROM matching_job WHERE report_id = ?", Integer.class, exhaustedId))
                .isEqualTo(5);
        assertThat(matchingWorker.processNext()).isFalse();
    }

    @Test
    void expiredLeaseAtAttemptLimitBecomesDeadWithoutAnotherAiCall() throws Exception {
        create("alice", "lost", 19.937, 50.061, "2026-01-01");
        var claimed = matchingJobs.claimNext().orElseThrow();
        jdbc.update("""
                UPDATE matching_job SET attempts = 5, locked_until = CURRENT_TIMESTAMP - interval '1 second'
                WHERE id = ?
                """, claimed.id());

        assertThat(matchingWorker.processNext()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE id = ?", String.class, claimed.id()))
                .isEqualTo("DEAD");
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void workerSkipsResolvedReportBeforeCallingAi() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        request(patch("/api/reports/" + reportId + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + report.path("version").asLong() + ",\"status\":\"RESOLVED\"}"), "alice", 200);

        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(reportId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, reportId))
                .isEqualTo("SUPERSEDED");
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void workerDoesNotPublishEmbeddingIfReportIsEditedDuringAiCall() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        when(embeddingClient.generate(anyString())).thenAnswer(invocation -> {
            var edited = new HashMap<>(content(19.937, 50.061, "2026-01-01"));
            edited.put("description", "Zupełnie inny opis przedmiotu po edycji.");
            request(put("/api/reports/" + reportId).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("version", report.path("version").asLong(),
                            "content", edited))), "alice", 200);
            return testEmbeddingResult();
        });

        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(reportId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ? AND report_version = 0", String.class, reportId))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ? AND report_version = 1", String.class, reportId))
                .isEqualTo("PENDING");
        doReturn(testEmbeddingResult()).when(embeddingClient).generate(anyString());
        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(reportId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ? AND report_version = 1", String.class, reportId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void workerDoesNotRecreateDeletedReportOrItsJob() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        when(embeddingClient.generate(anyString())).thenAnswer(invocation -> {
            request(delete("/api/reports/" + reportId).param("version", report.path("version").asText()), "alice", 204);
            return testEmbeddingResult();
        });

        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(reportId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job", Integer.class)).isZero();
    }

    @Test
    void workerRollsBackEmbeddingIfAnotherWorkerHasReclaimedTheLease() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        create("bob", "found", 19.937, 50.061, "2026-01-01");
        assertThat(matchingWorker.processNext()).isTrue();
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID reportId = UUID.fromString(report.path("id").asText());
        var currentLease = new java.util.concurrent.atomic.AtomicReference<ClaimedMatchingJob>();
        when(embeddingClient.generate(anyString())).thenAnswer(invocation -> {
            jdbc.update("UPDATE matching_job SET locked_until = CURRENT_TIMESTAMP - interval '1 second' WHERE report_id = ?", reportId);
            currentLease.set(matchingJobs.claimNext().orElseThrow());
            return testEmbeddingResult();
        });

        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(reportId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isZero();
        var stored = jdbc.queryForMap("SELECT status, lock_token, attempts FROM matching_job WHERE report_id = ?", reportId);
        assertThat(stored.get("status")).isEqualTo("PROCESSING");
        assertThat(stored.get("lock_token")).isEqualTo(currentLease.get().lockToken());
        assertThat(stored.get("attempts")).isEqualTo(2);
    }

    @Test
    void workerRegeneratesEmbeddingAfterEachTextFieldChanges() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        for (String field : List.of("title", "description", "color")) {
            JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
            UUID id = UUID.fromString(report.path("id").asText());
            assertThat(matchingWorker.processNext()).isTrue();
            var before = embeddings.findByReportId(id).orElseThrow();
            var changed = new HashMap<>(content(19.937, 50.061, "2026-01-01"));
            changed.put(field, "Zmieniona cecha przedmiotu");

            JsonNode edited = edit(report, changed);
            assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ? AND report_version = ?",
                    String.class, id, edited.path("version").asLong())).isEqualTo("PENDING");
            assertThat(matchingWorker.processNext()).isTrue();

            assertThat(embeddings.findByReportId(id).orElseThrow().inputHash()).isNotEqualTo(before.inputHash());
        }
        verify(embeddingClient, times(6)).generate(anyString());
    }

    @Test
    void workerReusesEmbeddingForRankingFieldsAndEquivalentWhitespace() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        for (String field : List.of("categoryCode", "location", "eventDate", "description")) {
            JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
            UUID id = UUID.fromString(report.path("id").asText());
            assertThat(matchingWorker.processNext()).isTrue();
            var before = embeddings.findByReportId(id).orElseThrow();
            var changed = new HashMap<>(content(19.937, 50.061, "2026-01-01"));
            changed.put(field, switch (field) {
                case "categoryCode" -> "OTHER";
                case "location" -> Map.of("label", "Warszawa", "longitude", 21.0122, "latitude", 52.2297);
                case "eventDate" -> "2026-01-02";
                default -> "Znaleziono  klucze\nz niebieskim brelokiem";
            });

            JsonNode edited = edit(report, changed);
            assertThat(matchingWorker.processNext()).isTrue();

            assertThat(embeddings.findByReportId(id)).contains(before);
            assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ? AND report_version = ?",
                    String.class, id, edited.path("version").asLong())).isEqualTo("COMPLETED");
        }
        verify(embeddingClient, times(4)).generate(anyString());
        verify(embeddingClient, times(8)).modelInfo();
    }

    @Test
    void workerDoesNotReuseIncompatibleSavedModelOrPreprocessingMetadata() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        var changes = Map.of("model_id", "different/model", "model_revision", "b".repeat(40), "preprocessing_version", "v0");
        for (var change : changes.entrySet()) {
            JsonNode report = create("alice", "found", 19.937, 50.061, "2026-01-01");
            UUID id = UUID.fromString(report.path("id").asText());
            assertThat(matchingWorker.processNext()).isTrue();
            // Column names come only from the fixed test map above.
            jdbc.update("UPDATE report_embedding SET " + change.getKey() + " = ? WHERE report_id = ?", change.getValue(), id);
            edit(report, content(19.937, 50.061, "2026-01-01"));

            assertThat(matchingWorker.processNext()).isTrue();

            var stored = embeddings.findByReportId(id).orElseThrow();
            assertThat(stored.modelId()).isEqualTo(testEmbeddingResult().modelId());
            assertThat(stored.modelRevision()).isEqualTo(testEmbeddingResult().modelRevision());
            assertThat(stored.preprocessingVersion()).isEqualTo("v1");
        }
        verify(embeddingClient, times(6)).generate(anyString());
    }

    @Test
    void modelRevisionChangeTriggersRegenerationEvenWithUnchangedText() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID id = UUID.fromString(report.path("id").asText());
        assertThat(matchingWorker.processNext()).isTrue();
        var before = embeddings.findByReportId(id).orElseThrow();
        when(embeddingClient.modelInfo()).thenReturn(new EmbeddingClient.ModelInfo("sdadas/mmlw-roberta-base", "b".repeat(40), 768));
        when(embeddingClient.generate(anyString())).thenReturn(new EmbeddingClient.EmbeddingResult(
                testEmbeddingResult().numbers(), "sdadas/mmlw-roberta-base", "b".repeat(40), 768));
        edit(report, content(19.937, 50.061, "2026-01-01"));

        assertThat(matchingWorker.processNext()).isTrue();

        var after = embeddings.findByReportId(id).orElseThrow();
        assertThat(after.inputHash()).isEqualTo(before.inputHash());
        assertThat(after.modelRevision()).isEqualTo("b".repeat(40));
        verify(embeddingClient, times(2)).generate(anyString());
    }

    @Test
    void editingAndSchedulingAreRolledBackTogether() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID id = UUID.fromString(report.path("id").asText());
        var updated = new ReportContent("Nowy tytuł", "KEYS", "Zmieniony opis kluczy.", "czerwony",
                LocalDate.of(2026, 1, 1), new ReportLocation("Kraków", 19.937, 50.061));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            reportService.update(id, new UpdateReportRequest(report.path("version").asLong(), updated), token("alice", "Alice"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE report_id = ?", Integer.class, id)).isEqualTo(2);
            throw new IllegalStateException("Simulated edit failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated edit failure");

        assertThat(jdbc.queryForObject("SELECT version FROM report WHERE id = ?", Long.class, id))
                .isEqualTo(report.path("version").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE report_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void workerAutomaticallyMatchesNewReportsAndReranksCachedEmbeddingAfterLocationEdit() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode lost = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID lostId = UUID.fromString(lost.path("id").asText());
        assertThat(matchingWorker.processNext()).isTrue();
        JsonNode found = create("bob", "found", 19.937, 50.061, "2026-01-02");
        UUID foundId = UUID.fromString(found.path("id").asText());
        assertThat(matchingWorker.processNext()).isTrue();
        var before = storedMatches.findByPair(lostId, foundId).orElseThrow();
        var embedding = embeddings.findByReportId(lostId).orElseThrow();
        assertThat(before.status()).isEqualTo(ReportMatchStatus.PROPOSED);

        var changed = new HashMap<>(content(21.0122, 52.2297, "2026-01-01"));
        edit(lost, changed);
        assertThat(storedMatches.findByPair(lostId, foundId).orElseThrow().status()).isEqualTo(ReportMatchStatus.EXPIRED);
        assertThat(matchingWorker.processNext()).isTrue();

        var after = storedMatches.findByPair(lostId, foundId).orElseThrow();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.status()).isEqualTo(ReportMatchStatus.PROPOSED);
        assertThat(after.finalScore()).isLessThan(before.finalScore());
        assertThat(after.lostReportVersion()).isEqualTo(1);
        assertThat(embeddings.findByReportId(lostId)).contains(embedding);
        verify(embeddingClient, times(2)).generate(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE status = 'COMPLETED'", Integer.class)).isEqualTo(3);
    }

    @Test
    void simultaneousEmbeddingGenerationDoesNotMissThePair() throws Exception {
        var bothGenerating = new java.util.concurrent.CountDownLatch(2);
        when(embeddingClient.generate(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            bothGenerating.countDown();
            assertThat(bothGenerating.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return testEmbeddingResult();
        });
        JsonNode lost = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        JsonNode found = create("bob", "found", 19.937, 50.061, "2026-01-02");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = executor.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(
                    matchingWorker::processNext, matchingWorker::processNext));
            for (var future : futures) assertThat(future.get()).isTrue();
        }
        assertThat(storedMatches.findByPair(UUID.fromString(lost.path("id").asText()),
                UUID.fromString(found.path("id").asText()))).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_embedding", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matching_job WHERE status = 'COMPLETED'", Integer.class)).isEqualTo(2);
    }

    @Test
    void matchPersistenceFailureRollsBackEmbeddingAndRetriesTheWholePublication() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        create("bob", "found", 19.937, 50.061, "2026-01-01");
        assertThat(matchingWorker.processNext()).isTrue();
        JsonNode lost = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        UUID lostId = UUID.fromString(lost.path("id").asText());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Simulated failure after match write");
        }).when(storedMatches).upsert(any(MatchingRankingService.RankedMatch.class), any(Instant.class));

        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(lostId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, lostId)).isEqualTo("PENDING");
        doCallRealMethod().when(storedMatches).upsert(any(MatchingRankingService.RankedMatch.class), any(Instant.class));
        jdbc.update("UPDATE matching_job SET available_at = CURRENT_TIMESTAMP - interval '1 second' WHERE report_id = ?", lostId);
        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(embeddings.findByReportId(lostId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, lostId)).isEqualTo("COMPLETED");
    }

    @Test
    void changingModelExpiresOldPairWhenTheOtherEmbeddingIsNoLongerCompatible() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode lost = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        assertThat(matchingWorker.processNext()).isTrue();
        JsonNode found = create("bob", "found", 19.937, 50.061, "2026-01-01");
        assertThat(matchingWorker.processNext()).isTrue();
        UUID lostId = UUID.fromString(lost.path("id").asText());
        UUID foundId = UUID.fromString(found.path("id").asText());
        assertThat(storedMatches.findByPair(lostId, foundId).orElseThrow().status()).isEqualTo(ReportMatchStatus.PROPOSED);
        when(embeddingClient.modelInfo()).thenReturn(new EmbeddingClient.ModelInfo("sdadas/mmlw-roberta-base", "b".repeat(40), 768));
        when(embeddingClient.generate(anyString())).thenReturn(new EmbeddingClient.EmbeddingResult(
                testEmbeddingResult().numbers(), "sdadas/mmlw-roberta-base", "b".repeat(40), 768));
        jdbc.update("UPDATE matching_job SET status = 'PENDING', attempts = 0 WHERE report_id = ?", lostId);

        assertThat(matchingWorker.processNext()).isTrue();
        assertThat(storedMatches.findByPair(lostId, foundId).orElseThrow().status()).isEqualTo(ReportMatchStatus.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT status FROM matching_job WHERE report_id = ?", String.class, lostId)).isEqualTo("COMPLETED");
    }

    @Test
    void migrationSchedulesPipelineV2ForExistingActiveReportsAndRetainsCompletedHistory() throws Exception {
        jdbc.execute("CREATE DATABASE matching_pipeline_upgrade");
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/matching_pipeline_upgrade");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .target("7").load().migrate();
            var upgrade = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            UUID author = UUID.randomUUID();
            UUID completed = UUID.randomUUID();
            UUID processing = UUID.randomUUID();
            UUID resolved = UUID.randomUUID();
            upgrade.update("""
                    INSERT INTO app_user (id, auth_issuer, auth_subject, display_name, created_at, updated_at)
                    VALUES (?, 'https://issuer.test', 'existing', 'Existing user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, author);
            for (UUID id : List.of(completed, processing, resolved)) {
                String status = id.equals(resolved) ? "RESOLVED" : "ACTIVE";
                upgrade.update("""
                        INSERT INTO report (id, author_id, category_id, title, type, status, description,
                            location_label, location_point, event_date, resolved_at, created_at, updated_at)
                        SELECT ?, ?, id, 'Existing report', 'LOST', ?, 'Existing report description', 'Kraków',
                            ST_SetSRID(ST_MakePoint(19.937, 50.061), 4326)::geography, DATE '2026-01-01',
                            CASE WHEN ? = 'RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        FROM category WHERE code = 'KEYS'
                        """, id, author, status, status);
            }
            upgrade.update("""
                    INSERT INTO matching_job (id, report_id, report_version, pipeline_version, status, attempts,
                        available_at, created_at, updated_at)
                    VALUES (?, ?, 0, 'v1', 'COMPLETED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), completed);
            upgrade.update("""
                    INSERT INTO matching_job (id, report_id, report_version, pipeline_version, status, attempts,
                        available_at, locked_until, lock_token, created_at, updated_at)
                    VALUES (?, ?, 0, 'v1', 'PROCESSING', 1, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + interval '2 minutes', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), processing, UUID.randomUUID());

            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load().migrate();

            assertThat(upgrade.queryForList("SELECT report_id FROM matching_job WHERE pipeline_version = 'v2'", UUID.class))
                    .containsExactlyInAnyOrder(completed, processing);
            assertThat(upgrade.queryForObject("SELECT count(*) FROM matching_job WHERE pipeline_version = 'v2' AND status = 'PENDING' AND attempts = 0",
                    Integer.class)).isEqualTo(2);
            assertThat(upgrade.queryForObject("SELECT status FROM matching_job WHERE report_id = ? AND pipeline_version = 'v1'",
                    String.class, completed)).isEqualTo("COMPLETED");
            var superseded = upgrade.queryForMap("SELECT status, lock_token, locked_until FROM matching_job WHERE report_id = ? AND pipeline_version = 'v1'", processing);
            assertThat(superseded.get("status")).isEqualTo("SUPERSEDED");
            assertThat(superseded.get("lock_token")).isNull();
            assertThat(superseded.get("locked_until")).isNull();
            var before = upgrade.queryForList("SELECT * FROM matching_job WHERE pipeline_version = 'v2' ORDER BY id");
            upgrade.execute(java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migration/V8__enqueue_matching_pipeline_v2.sql")));
            assertThat(upgrade.queryForList("SELECT * FROM matching_job WHERE pipeline_version = 'v2' ORDER BY id")).isEqualTo(before);
        } finally {
            jdbc.execute("DROP DATABASE matching_pipeline_upgrade");
        }
    }

    @Test
    void matchesEndpointRequiresAuthenticationAndOwnershipFromBothSides() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode lost = createAndProcess("alice", "lost", 19.937, "2026-01-01");
        JsonNode found = createAndProcess("bob", "found", 19.937, "2026-01-02");
        String lostPath = "/api/reports/" + lost.path("id").asText() + "/matches";
        String foundPath = "/api/reports/" + found.path("id").asText() + "/matches";
        request(get(lostPath), null, 401);
        request(get(lostPath), "invalid", 401);
        request(get(lostPath), "bob", 403);
        request(get(foundPath), "alice", 403);
        request(get("/api/reports/" + UUID.randomUUID() + "/matches"), "alice", 404);
        when(decoder.decode("charlie")).thenReturn(token("charlie", "Charlie"));
        request(get(lostPath), "charlie", 403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(2);
        var aliceView = request(get(lostPath), "alice", 200);
        var bobView = request(get(foundPath), "bob", 200);
        assertThat(aliceView.path("totalElements").asLong()).isEqualTo(1);
        assertThat(aliceView.path("content").get(0).path("report").path("id").asText()).isEqualTo(found.path("id").asText());
        assertThat(bobView.path("content").get(0).path("report").path("id").asText()).isEqualTo(lost.path("id").asText());
        assertThat(aliceView.path("content").get(0).path("id")).isEqualTo(bobView.path("content").get(0).path("id"));
    }

    @Test
    void matchesEndpointReturnsSafeRoundedSummaryWithoutAiCallsOrWrites() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode lost = createAndProcess("alice", "lost", 19.937, "2026-01-01");
        JsonNode found = createAndProcess("bob", "found", 19.938, "2026-01-02");
        var before = jdbc.queryForMap("SELECT * FROM report_match");
        clearInvocations(embeddingClient);
        var response = request(get("/api/reports/" + lost.path("id").asText() + "/matches"), "alice", 200);
        var match = response.path("content").get(0);
        assertThat(match.path("status").asText()).isEqualTo("PROPOSED");
        assertThat(match.path("report").path("id").asText()).isEqualTo(found.path("id").asText());
        assertThat(match.path("report").path("title").asText()).isEqualTo("Klucze z brelokiem");
        assertThat(match.path("report").path("type").asText()).isEqualTo("found");
        assertThat(match.path("report").path("categoryCode").asText()).isEqualTo("KEYS");
        assertThat(match.path("report").path("location").asText()).isEqualTo("Kraków, Stare Miasto");
        assertThat(match.path("approximateDistanceKm").asLong()).isZero();
        assertThat(match.path("daysAfterLoss").asLong()).isEqualTo(1);
        double score = match.path("finalScore").asDouble();
        assertThat(score).isBetween(0.0, 1.0);
        assertThat(score * 100).isCloseTo(Math.rint(score * 100), within(1e-10));
        assertThat(response.toString()).doesNotContain("authorId", "email", "latitude", "longitude", "locationPoint",
                "embedding", "inputHash", "modelRevision", "description", "distanceMeters");
        assertThat(jdbc.queryForMap("SELECT * FROM report_match")).isEqualTo(before);
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void matchesEndpointPaginatesByScoreWithStableTiesAndAccurateOutOfRangeCount() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode lost = createAndProcess("alice", "lost", 19.937, "2026-01-01");
        JsonNode first = createAndProcess("bob", "found", 19.937, "2026-01-01");
        JsonNode farther = createAndProcess("bob", "found", 21.0122, "2026-01-01");
        JsonNode second = createAndProcess("bob", "found", 19.937, "2026-01-01");
        UUID lostId = UUID.fromString(lost.path("id").asText());
        var tiedIds = new ArrayList<>(List.of(
                storedMatches.findByPair(lostId, UUID.fromString(first.path("id").asText())).orElseThrow().id(),
                storedMatches.findByPair(lostId, UUID.fromString(second.path("id").asText())).orElseThrow().id()));
        tiedIds.sort(Comparator.comparing(UUID::toString));
        var expected = new ArrayList<>(tiedIds);
        expected.add(storedMatches.findByPair(lostId, UUID.fromString(farther.path("id").asText())).orElseThrow().id());
        String path = "/api/reports/" + lostId + "/matches";
        for (int page = 0; page < 3; page++) {
            var response = request(get(path).param("page", Integer.toString(page)).param("size", "1"), "alice", 200);
            assertThat(response.path("content").size()).isEqualTo(1);
            assertThat(response.path("content").get(0).path("id").asText()).isEqualTo(expected.get(page).toString());
            assertThat(response.path("totalElements").asLong()).isEqualTo(3);
            assertThat(response.path("totalPages").asLong()).isEqualTo(3);
        }
        var beyond = request(get(path).param("page", "99").param("size", "1"), "alice", 200);
        assertThat(beyond.path("content").size()).isZero();
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(3);
        assertThat(beyond.path("totalPages").asLong()).isEqualTo(3);
    }

    @Test
    void matchesEndpointHidesStaleFingerprintsBeforeWorkerExpiresThePair() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        for (String field : List.of("input_hash", "model_revision", "model_id", "preprocessing_version", "updated_at")) {
            JsonNode lost = createAndProcess("alice", "lost", 19.937, "2026-01-01");
            JsonNode found = createAndProcess("bob", "found", 19.937, "2026-01-01");
            UUID foundId = UUID.fromString(found.path("id").asText());
            if (field.equals("updated_at")) {
                jdbc.update("UPDATE report_embedding SET updated_at = updated_at + interval '1 second' WHERE report_id = ?", foundId);
            } else {
                String changed = field.equals("input_hash") ? "b".repeat(64)
                        : field.equals("model_revision") ? "b".repeat(40) : "obsolete";
                jdbc.update("UPDATE report_embedding SET " + field + " = ? WHERE report_id = ?", changed, foundId);
            }
            var response = request(get("/api/reports/" + lost.path("id").asText() + "/matches"), "alice", 200);
            assertThat(response.path("totalElements").asLong()).isZero();
            assertThat(response.path("content").size()).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM report_match", String.class)).isEqualTo("PROPOSED");
            jdbc.update("DELETE FROM report");
        }
    }

    @Test
    void matchesEndpointFiltersStatusesVersionsAndRankingPolicy() throws Exception {
        when(embeddingClient.generate(anyString())).thenReturn(testEmbeddingResult());
        JsonNode lost = createAndProcess("alice", "lost", 19.937, "2026-01-01");
        JsonNode found = createAndProcess("bob", "found", 19.937, "2026-01-01");
        String path = "/api/reports/" + lost.path("id").asText() + "/matches";
        jdbc.update("UPDATE report_match SET status = 'CONFIRMED'");
        assertThat(request(get(path), "alice", 200).path("totalElements").asLong()).isEqualTo(1);
        for (String status : List.of("REJECTED", "EXPIRED")) {
            jdbc.update("UPDATE report_match SET status = ?", status);
            assertThat(request(get(path), "alice", 200).path("totalElements").asLong()).isZero();
        }
        jdbc.update("UPDATE report_match SET status = 'PROPOSED', ranking_version = 'obsolete'");
        assertThat(request(get(path), "alice", 200).path("totalElements").asLong()).isZero();
        jdbc.update("UPDATE report_match SET ranking_version = 'v1'");
        jdbc.update("UPDATE report SET version = version + 1 WHERE id = ?", UUID.fromString(found.path("id").asText()));
        assertThat(request(get(path), "alice", 200).path("totalElements").asLong()).isZero();
    }

    @Test
    void matchesEndpointIsEmptyWhileAnalysisIsPendingAndValidatesPagination() throws Exception {
        JsonNode report = create("alice", "lost", 19.937, 50.061, "2026-01-01");
        String path = "/api/reports/" + report.path("id").asText() + "/matches";
        var response = request(get(path), "alice", 200);
        assertThat(response.path("content").size()).isZero();
        assertThat(response.path("page").asInt()).isZero();
        assertThat(response.path("size").asInt()).isEqualTo(20);
        assertThat(response.path("totalElements").asLong()).isZero();
        request(get(path).param("page", "-1"), "alice", 400);
        request(get(path).param("size", "0"), "alice", 400);
        request(get(path).param("size", "101"), "alice", 400);
        request(get("/api/reports/not-a-uuid/matches"), "alice", 400);
    }

    @Test
    void publicReadsAndProtectedOperations() throws Exception {
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(12));
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
                .andExpect(jsonPath("$.paths['/api/reports/{id}/matches'].get").exists())
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(12);
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

    private JsonNode createAndProcess(String user, String type, double longitude, String date) throws Exception {
        JsonNode report = create(user, type, longitude, 50.061, date);
        assertThat(matchingWorker.processNext()).isTrue();
        return report;
    }

    private JsonNode edit(JsonNode report, Map<String, Object> changedContent) throws Exception {
        return request(put("/api/reports/" + report.path("id").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", report.path("version").asLong(),
                        "content", changedContent))), "alice", 200);
    }

    private EmbeddingClient.EmbeddingResult testEmbeddingResult() {
        var numbers = new ArrayList<>(Collections.nCopies(768, 0.0));
        numbers.set(0, 1.0);
        return new EmbeddingClient.EmbeddingResult(numbers, "sdadas/mmlw-roberta-base", "a".repeat(40), 768);
    }

    private String testEmbeddingVector() {
        return "[1," + String.join(",", Collections.nCopies(767, "0")) + "]";
    }

    private void insertTestEmbedding(UUID reportId, String vector) {
        jdbc.update("""
                INSERT INTO report_embedding
                    (report_id, embedding, model_id, model_revision, preprocessing_version, input_hash, updated_at)
                VALUES (?, CAST(? AS vector), ?, ?, ?, ?, ?)
                """, reportId, vector, "sdadas/mmlw-roberta-base", "test-revision", "v1",
                "a".repeat(64), java.sql.Timestamp.from(Instant.parse("2026-01-01T12:00:00Z")));
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
