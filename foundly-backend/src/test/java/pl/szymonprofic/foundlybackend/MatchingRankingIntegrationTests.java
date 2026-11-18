package pl.szymonprofic.foundlybackend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.ImageFromDockerfile;
import pl.szymonprofic.foundlybackend.models.ReportEmbedding;
import pl.szymonprofic.foundlybackend.models.enums.ReportType;
import pl.szymonprofic.foundlybackend.repositories.ReportEmbeddingRepository;
import pl.szymonprofic.foundlybackend.services.EmbeddingTextBuilder;
import pl.szymonprofic.foundlybackend.services.MatchingRankingService;
import pl.szymonprofic.foundlybackend.services.ReportMatchService;
import pl.szymonprofic.foundlybackend.services.ReportMatchService.SaveOutcome;
import pl.szymonprofic.foundlybackend.services.ReportService;
import pl.szymonprofic.foundlybackend.repositories.ReportMatchRepository;
import pl.szymonprofic.foundlybackend.models.ReportMatchStatus;
import pl.szymonprofic.foundlybackend.dto.UpdateReportRequest;
import pl.szymonprofic.foundlybackend.dto.ReportContent;
import pl.szymonprofic.foundlybackend.dto.ReportLocation;
import pl.szymonprofic.foundlybackend.dto.ResolveReportRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"foundly.matching.enabled=false", "foundly.matching.ranking.min-semantic-score=0.5"})
@Testcontainers(disabledWithoutDocker = true)
class MatchingRankingIntegrationTests {
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
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportEmbeddingRepository embeddings;
    @Autowired EmbeddingTextBuilder builder;
    @Autowired MatchingRankingService ranking;
    @Autowired ReportMatchService matchService;
    @Autowired ReportMatchRepository matches;
    @Autowired ReportService reports;
    @Autowired PlatformTransactionManager transactionManager;
    private UUID alice;
    private UUID bob;
    private int sequence;
    private static final LocalDate DATE = LocalDate.of(2026, 1, 1);
    private static final String TITLE = "Klucze z brelokiem";
    private static final String DESCRIPTION = "Klucze z niebieskim brelokiem.";

    @BeforeEach
    void seedAuthors() {
        jdbc.update("DELETE FROM report");
        jdbc.update("DELETE FROM app_user");
        sequence = 0;
        alice = author("alice");
        bob = author("bob");
    }

    @Test
    void savesScoresAndProvenanceAndRepeatedSaveDoesNotChangeTheRecord() {
        var evaluation = pair();
        assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.SAVED);
        var stored = matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ReportMatchStatus.PROPOSED);
        assertThat(stored.version()).isZero();
        assertThat(stored.lostReportVersion()).isEqualTo(evaluation.lostReportVersion());
        assertThat(stored.foundReportVersion()).isEqualTo(evaluation.foundReportVersion());
        assertThat(stored.lostInputHash()).isEqualTo(evaluation.lostInputHash());
        assertThat(stored.foundInputHash()).isEqualTo(evaluation.foundInputHash());
        assertThat(stored.lostEmbeddingUpdatedAt()).isEqualTo(evaluation.lostEmbeddingUpdatedAt());
        assertThat(stored.foundEmbeddingUpdatedAt()).isEqualTo(evaluation.foundEmbeddingUpdatedAt());
        assertThat(stored.modelId()).isEqualTo(evaluation.modelId());
        assertThat(stored.modelRevision()).isEqualTo(evaluation.modelRevision());
        assertThat(stored.preprocessingVersion()).isEqualTo(evaluation.preprocessingVersion());
        assertThat(stored.rankingVersion()).isEqualTo(evaluation.rankingVersion());
        assertThat(stored.semanticScore()).isEqualTo(evaluation.scores().semantic());
        assertThat(stored.locationScore()).isEqualTo(evaluation.scores().location());
        assertThat(stored.dateScore()).isEqualTo(evaluation.scores().date());
        assertThat(stored.categoryScore()).isEqualTo(evaluation.scores().category());
        assertThat(stored.finalScore()).isEqualTo(evaluation.scores().finalScore());
        assertThat(stored.evidenceCoverage()).isEqualTo(evaluation.scores().evidenceCoverage());
        assertThat(stored.distanceMeters()).isEqualTo(evaluation.distanceMeters());
        assertThat(stored.daysAfterLoss()).isEqualTo(evaluation.daysAfterLoss());

        var reverse = ranking.rank(evaluation.foundReportId(), 10).getFirst();
        assertThat(matchService.saveIfCurrent(reverse)).isEqualTo(SaveOutcome.UNCHANGED);
        assertThat(matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId())).contains(stored);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isEqualTo(1);
    }

    @Test
    void editsExpireThePairAndNewEvaluationUpdatesTheSameRecordThenResolutionExpiresAndDeletionRemovesIt() {
        var evaluation = pair();
        assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.SAVED);
        var original = matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId()).orElseThrow();

        reports.update(evaluation.lostReportId(), new UpdateReportRequest(0L,
                new ReportContent(TITLE, "KEYS", DESCRIPTION, "niebieski", DATE,
                        new ReportLocation("Warszawa", 21.0122, 52.2297))), jwt("alice"));
        assertThat(matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId()).orElseThrow().status())
                .isEqualTo(ReportMatchStatus.EXPIRED);
        assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.STALE);

        var refreshed = ranking.rank(evaluation.lostReportId(), 10).getFirst();
        assertThat(matchService.saveIfCurrent(refreshed)).isEqualTo(SaveOutcome.SAVED);
        var updated = matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId()).orElseThrow();
        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.status()).isEqualTo(ReportMatchStatus.PROPOSED);
        assertThat(updated.lostReportVersion()).isEqualTo(1);
        assertThat(updated.finalScore()).isLessThan(original.finalScore());
        assertThat(updated.version()).isEqualTo(2);

        reports.resolve(evaluation.foundReportId(), new ResolveReportRequest(0L, "RESOLVED"), jwt("bob"));
        assertThat(matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId()).orElseThrow().status())
                .isEqualTo(ReportMatchStatus.EXPIRED);
        reports.delete(evaluation.foundReportId(), 1, jwt("bob"));
        assertThat(matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId())).isEmpty();
        assertThat(matchService.saveIfCurrent(refreshed)).isEqualTo(SaveOutcome.STALE);
    }

    @Test
    void changedEmbeddingFingerprintPreventsSavingAnOldEvaluation() {
        var changes = Map.of("input_hash", "b".repeat(64), "model_revision", "b".repeat(40),
                "model_id", "different/model", "preprocessing_version", "v0");
        for (var change : changes.entrySet()) {
            var evaluation = pair();
            jdbc.update("UPDATE report_embedding SET " + change.getKey() + " = ? WHERE report_id = ?",
                    change.getValue(), evaluation.foundReportId());
            assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.STALE);
        }
        var evaluation = pair();
        jdbc.update("UPDATE report_embedding SET updated_at = updated_at + interval '1 second' WHERE report_id = ?",
                evaluation.lostReportId());
        assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isZero();
    }

    @Test
    void missingEmbeddingSameAuthorAndWrongReportTypeAreRejectedAtSaveTime() {
        var missing = pair();
        jdbc.update("DELETE FROM report_embedding WHERE report_id = ?", missing.foundReportId());
        assertThat(matchService.saveIfCurrent(missing)).isEqualTo(SaveOutcome.STALE);
        var sameAuthor = pair();
        jdbc.update("UPDATE report SET author_id = ? WHERE id = ?", alice, sameAuthor.foundReportId());
        assertThat(matchService.saveIfCurrent(sameAuthor)).isEqualTo(SaveOutcome.STALE);
        var wrongType = pair();
        jdbc.update("UPDATE report SET type = 'FOUND' WHERE id = ?", wrongType.lostReportId());
        assertThat(matchService.saveIfCurrent(wrongType)).isEqualTo(SaveOutcome.STALE);
    }

    @Test
    void automaticSavePreservesRejectedAndConfirmedStatuses() {
        var evaluation = pair();
        matchService.saveIfCurrent(evaluation);
        for (String status : List.of("REJECTED", "CONFIRMED")) {
            jdbc.update("UPDATE report_match SET status = ? WHERE lost_report_id = ? AND found_report_id = ?",
                    status, evaluation.lostReportId(), evaluation.foundReportId());
            var before = matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId()).orElseThrow();
            assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.PROTECTED);
            assertThat(matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId())).contains(before);
        }
    }

    @Test
    void parallelSavesCreateOnePairAndTheOtherCallsAreIdempotent() throws Exception {
        var evaluation = pair();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> (java.util.concurrent.Callable<SaveOutcome>) () -> matchService.saveIfCurrent(evaluation))
                    .toList();
            var outcomes = new ArrayList<SaveOutcome>();
            for (var future : executor.invokeAll(tasks)) outcomes.add(future.get());
            assertThat(outcomes).containsExactlyInAnyOrder(SaveOutcome.SAVED, SaveOutcome.UNCHANGED,
                    SaveOutcome.UNCHANGED, SaveOutcome.UNCHANGED, SaveOutcome.UNCHANGED, SaveOutcome.UNCHANGED);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_match", Integer.class)).isEqualTo(1);
    }

    @Test
    void matchSaveIsRolledBackWithItsTransaction() {
        var evaluation = pair();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(matchService.saveIfCurrent(evaluation)).isEqualTo(SaveOutcome.SAVED);
            throw new IllegalStateException("Simulated failure after match save");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated failure after match save");
        assertThat(matches.findByPair(evaluation.lostReportId(), evaluation.foundReportId())).isEmpty();
    }

    @Test
    void nullableCategoryScoreIsPreservedInTheStoredMatch() {
        UUID lost = report(ReportType.LOST, alice, "OTHER", 19.937, DATE, 1);
        UUID found = report(ReportType.FOUND, bob, "OTHER", 19.937, DATE, 0.9);
        assertThat(matchService.saveIfCurrent(ranking.rank(lost, 10).getFirst())).isEqualTo(SaveOutcome.SAVED);
        var stored = matches.findByPair(lost, found).orElseThrow();
        assertThat(stored.categoryScore()).isNull();
        assertThat(stored.evidenceCoverage()).isCloseTo(0.9, within(1e-12));
    }

    @Test
    void rejectedPairDoesNotConsumeTheLimitForOtherCandidates() {
        var rejected = pair();
        matchService.saveIfCurrent(rejected);
        jdbc.update("UPDATE report_match SET status = 'REJECTED'");
        UUID other = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.8);
        assertThat(ranking.rank(rejected.lostReportId(), 1)).extracting(MatchingRankingService.RankedMatch::foundReportId)
                .containsExactly(other);
    }

    @Test
    void excludesWrongTypesSameAuthorInactiveMissingAndIncompatibleEmbeddingsButAllowsDifferentCategories() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID valid = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        UUID differentCategory = report(ReportType.FOUND, bob, "ELECTRONICS", 19.937, DATE, 0.9);
        report(ReportType.LOST, bob, "KEYS", 19.937, DATE, 1);
        report(ReportType.FOUND, alice, "KEYS", 19.937, DATE, 1);
        UUID inactive = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 1);
        jdbc.update("UPDATE report SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?", inactive);
        UUID missing = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 1);
        jdbc.update("DELETE FROM report_embedding WHERE report_id = ?", missing);
        for (String field : List.of("model_id", "model_revision", "preprocessing_version")) {
            UUID incompatible = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 1);
            jdbc.update("UPDATE report_embedding SET " + field + " = 'incompatible' WHERE report_id = ?", incompatible);
        }

        assertThat(ranking.rank(lost, 100)).extracting(MatchingRankingService.RankedMatch::foundReportId)
                .containsExactly(valid, differentCategory);
    }

    @Test
    void hybridRankingAndLimitAreAppliedAfterAllSignalsRatherThanSemanticTopOne() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        report(ReportType.FOUND, bob, "KEYS", 24.0, DATE.plusDays(1), 1);
        UUID nearby = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE.plusDays(1), 0.8);

        var best = ranking.rank(lost, 1).getFirst();
        assertThat(best.foundReportId()).isEqualTo(nearby);
        assertThat(best.scores().semantic()).isCloseTo(0.8, within(1e-6));
        assertThat(best.distanceMeters()).isZero();
        assertThat(best.daysAfterLoss()).isEqualTo(1);
        assertThat(best.rankingVersion()).isEqualTo("v1");
    }

    @Test
    void semanticThresholdCannotBeCompensatedByPerfectLocationDateAndCategory() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.49);
        UUID valid = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.8);
        assertThat(ranking.rank(lost, 10)).extracting(MatchingRankingService.RankedMatch::foundReportId).containsExactly(valid);
    }

    @Test
    void excludesCandidateWhoseTextChangedBeforeEmbeddingWasRegenerated() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID found = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        jdbc.update("UPDATE report SET description = 'Zupełnie inny opis przedmiotu', version = version + 1 WHERE id = ?", found);
        assertThat(ranking.rank(lost, 10)).isEmpty();
    }

    @Test
    void staleSourceOrObsoletePreprocessingDoesNotProduceRanking() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        jdbc.update("UPDATE report_embedding SET preprocessing_version = 'v0'");
        assertThat(ranking.rank(lost, 10)).isEmpty();
        jdbc.update("UPDATE report_embedding SET preprocessing_version = 'v1'");
        jdbc.update("UPDATE report SET title = 'Inny przedmiot', version = version + 1 WHERE id = ?", lost);
        assertThat(ranking.rank(lost, 10)).isEmpty();
    }

    @Test
    void usesCurrentRankingFieldsAndDistancesInMetersWithoutRequiringANewTextEmbedding() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID found = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        jdbc.update("""
                UPDATE report SET location_point = ST_SetSRID(ST_MakePoint(19.938, 50.061), 4326)::geography,
                    event_date = ?, category_id = (SELECT id FROM category WHERE code = 'OTHER'), version = version + 1
                WHERE id = ?
                """, DATE.plusDays(3), found);

        var match = ranking.rank(lost, 10).getFirst();
        assertThat(match.distanceMeters()).isBetween(60.0, 80.0);
        assertThat(match.daysAfterLoss()).isEqualTo(3);
        assertThat(match.foundReportVersion()).isEqualTo(1);
        assertThat(match.scores().category()).isNull();
        assertThat(match.scores().evidenceCoverage()).isCloseTo(0.9, within(1e-12));
    }

    @Test
    void samePairHasTheSameCanonicalResultWhenStartingFromLostOrFound() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID found = report(ReportType.FOUND, bob, "KEYS", 19.938, DATE.plusDays(4), 0.9);
        assertThat(ranking.rank(lost, 10).getFirst()).isEqualTo(ranking.rank(found, 10).getFirst());
    }

    @Test
    void tiesHaveStableIdOrdering() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID first = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        UUID second = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        assertThat(ranking.rank(lost, 1)).extracting(MatchingRankingService.RankedMatch::foundReportId).containsExactly(first);
        assertThat(ranking.rank(lost, 2)).extracting(MatchingRankingService.RankedMatch::foundReportId).containsExactly(first, second);
    }

    @Test
    void invalidZeroVectorIsNotRankedAsIfItWereASimilarItem() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID found = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        String zeroVector = "[" + String.join(",", Collections.nCopies(768, "0")) + "]";
        jdbc.update("UPDATE report_embedding SET embedding = CAST(? AS vector) WHERE report_id = ?", zeroVector, found);
        assertThat(ranking.rank(lost, 10)).isEmpty();
    }

    @Test
    void absentInactiveOrUnembeddedSourceReturnsNoSuggestionsAndLimitIsValidated() {
        assertThat(ranking.rank(UUID.randomUUID(), 10)).isEmpty();
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        jdbc.update("DELETE FROM report_embedding WHERE report_id = ?", lost);
        assertThat(ranking.rank(lost, 10)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> ranking.rank(lost, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> ranking.rank(lost, 101));
    }

    private UUID author(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user (id, auth_issuer, auth_subject, display_name, created_at, updated_at)
                VALUES (?, 'https://issuer.test', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, name, name);
        return id;
    }

    private UUID report(ReportType type, UUID author, String category, double longitude, LocalDate date, double cosine) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", ++sequence));
        jdbc.update("""
                INSERT INTO report (id, author_id, category_id, title, type, description, color, location_label,
                    location_point, event_date, created_at, updated_at)
                VALUES (?, ?, (SELECT id FROM category WHERE code = ?), ?, ?, ?, 'niebieski', 'Kraków',
                    ST_SetSRID(ST_MakePoint(?, 50.061), 4326)::geography, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, author, category, TITLE, type.name(), DESCRIPTION, longitude, date);
        var prepared = builder.build(type, TITLE, DESCRIPTION, "niebieski");
        var vector = new ArrayList<>(Collections.nCopies(768, 0.0));
        vector.set(0, cosine);
        vector.set(1, Math.sqrt(1 - cosine * cosine));
        embeddings.save(new ReportEmbedding(id, vector, "sdadas/mmlw-roberta-base", "a".repeat(40),
                prepared.preprocessingVersion(), prepared.inputHash(), Instant.parse("2026-01-01T12:00:00Z")));
        return id;
    }

    private MatchingRankingService.RankedMatch pair() {
        UUID lost = report(ReportType.LOST, alice, "KEYS", 19.937, DATE, 1);
        UUID found = report(ReportType.FOUND, bob, "KEYS", 19.937, DATE, 0.9);
        return ranking.rank(lost, 100).stream().filter(match -> match.foundReportId().equals(found)).findFirst().orElseThrow();
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue(subject).header("alg", "none").issuer("https://issuer.test").subject(subject).build();
    }
}
