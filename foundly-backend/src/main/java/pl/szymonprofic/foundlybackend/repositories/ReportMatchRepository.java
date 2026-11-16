package pl.szymonprofic.foundlybackend.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.szymonprofic.foundlybackend.models.ReportMatch;
import pl.szymonprofic.foundlybackend.models.ReportMatchStatus;
import pl.szymonprofic.foundlybackend.services.MatchingRankingService.RankedMatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReportMatchRepository {
    private final JdbcTemplate jdbc;

    public void lockPublicationInputs(UUID reportId) {
        // Shared across worker instances; held only during the short result transaction, never during AI.
        jdbc.query("SELECT pg_advisory_xact_lock(724013, 1)", row -> null);
        // Lock the whole input set in a stable order before holding the source report lock.
        // This also keeps repeated saveIfCurrent calls in one transaction from reversing lock order.
        jdbc.queryForList("""
                SELECT r.id FROM report r JOIN report s ON s.id = ?
                WHERE r.id = s.id OR (r.status = 'ACTIVE' AND r.type <> s.type AND r.author_id <> s.author_id)
                ORDER BY r.id FOR UPDATE OF r
                """, UUID.class, reportId);
    }

    public List<ReportState> lockReports(UUID lostId, UUID foundId) {
        return jdbc.query("""
                SELECT id, type, status, author_id, version FROM report
                WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """, (row, index) -> new ReportState(row.getObject("id", UUID.class), row.getString("type"),
                row.getString("status"), row.getObject("author_id", UUID.class), row.getLong("version")), lostId, foundId);
    }

    public List<EmbeddingState> lockEmbeddings(UUID lostId, UUID foundId) {
        return jdbc.query("""
                SELECT report_id, input_hash, model_id, model_revision, preprocessing_version, updated_at
                FROM report_embedding WHERE report_id IN (?, ?) ORDER BY report_id FOR SHARE
                """, (row, index) -> new EmbeddingState(row.getObject("report_id", UUID.class), row.getString("input_hash"),
                row.getString("model_id"), row.getString("model_revision"), row.getString("preprocessing_version"),
                row.getTimestamp("updated_at").toInstant()), lostId, foundId);
    }

    public Optional<ReportMatch> findByPair(UUID lostId, UUID foundId) {
        return findByPair(lostId, foundId, false);
    }

    public Optional<ReportMatch> findByPairForUpdate(UUID lostId, UUID foundId) {
        return findByPair(lostId, foundId, true);
    }

    private Optional<ReportMatch> findByPair(UUID lostId, UUID foundId, boolean lock) {
        return jdbc.query("SELECT * FROM report_match WHERE lost_report_id = ? AND found_report_id = ?"
                + (lock ? " FOR UPDATE" : ""), (row, index) -> new ReportMatch(
                row.getObject("id", UUID.class), row.getObject("lost_report_id", UUID.class), row.getObject("found_report_id", UUID.class),
                row.getLong("lost_report_version"), row.getLong("found_report_version"), row.getString("lost_input_hash"), row.getString("found_input_hash"),
                row.getTimestamp("lost_embedding_updated_at").toInstant(), row.getTimestamp("found_embedding_updated_at").toInstant(),
                row.getString("model_id"), row.getString("model_revision"), row.getString("preprocessing_version"), row.getString("ranking_version"),
                row.getDouble("semantic_score"), row.getObject("location_score", Double.class), row.getObject("date_score", Double.class),
                row.getObject("category_score", Double.class), row.getDouble("final_score"), row.getDouble("evidence_coverage"),
                row.getObject("distance_meters", Double.class), row.getObject("days_after_loss", Long.class),
                ReportMatchStatus.valueOf(row.getString("status")), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getLong("version")), lostId, foundId).stream().findFirst();
    }

    public boolean upsert(RankedMatch match, Instant now) {
        var scores = match.scores();
        return jdbc.update("""
                INSERT INTO report_match AS stored
                    (id, lost_report_id, found_report_id, lost_report_version, found_report_version,
                     lost_input_hash, found_input_hash, lost_embedding_updated_at, found_embedding_updated_at,
                     model_id, model_revision, preprocessing_version, ranking_version,
                     semantic_score, location_score, date_score, category_score, final_score, evidence_coverage,
                     distance_meters, days_after_loss, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (lost_report_id, found_report_id) DO UPDATE SET
                    lost_report_version = EXCLUDED.lost_report_version, found_report_version = EXCLUDED.found_report_version,
                    lost_input_hash = EXCLUDED.lost_input_hash, found_input_hash = EXCLUDED.found_input_hash,
                    lost_embedding_updated_at = EXCLUDED.lost_embedding_updated_at,
                    found_embedding_updated_at = EXCLUDED.found_embedding_updated_at,
                    model_id = EXCLUDED.model_id, model_revision = EXCLUDED.model_revision,
                    preprocessing_version = EXCLUDED.preprocessing_version, ranking_version = EXCLUDED.ranking_version,
                    semantic_score = EXCLUDED.semantic_score, location_score = EXCLUDED.location_score,
                    date_score = EXCLUDED.date_score, category_score = EXCLUDED.category_score,
                    final_score = EXCLUDED.final_score, evidence_coverage = EXCLUDED.evidence_coverage,
                    distance_meters = EXCLUDED.distance_meters, days_after_loss = EXCLUDED.days_after_loss,
                    status = 'PROPOSED', updated_at = EXCLUDED.updated_at, version = stored.version + 1
                WHERE stored.status IN ('PROPOSED', 'EXPIRED') AND
                    (stored.lost_report_version, stored.found_report_version, stored.lost_input_hash, stored.found_input_hash,
                     stored.lost_embedding_updated_at, stored.found_embedding_updated_at, stored.model_id,
                     stored.model_revision, stored.preprocessing_version, stored.ranking_version,
                     stored.semantic_score, stored.location_score, stored.date_score, stored.category_score,
                     stored.final_score, stored.evidence_coverage, stored.distance_meters, stored.days_after_loss, stored.status)
                    IS DISTINCT FROM
                    (EXCLUDED.lost_report_version, EXCLUDED.found_report_version, EXCLUDED.lost_input_hash, EXCLUDED.found_input_hash,
                     EXCLUDED.lost_embedding_updated_at, EXCLUDED.found_embedding_updated_at, EXCLUDED.model_id,
                     EXCLUDED.model_revision, EXCLUDED.preprocessing_version, EXCLUDED.ranking_version,
                     EXCLUDED.semantic_score, EXCLUDED.location_score, EXCLUDED.date_score, EXCLUDED.category_score,
                     EXCLUDED.final_score, EXCLUDED.evidence_coverage, EXCLUDED.distance_meters, EXCLUDED.days_after_loss, 'PROPOSED')
                """, UUID.randomUUID(), match.lostReportId(), match.foundReportId(), match.lostReportVersion(), match.foundReportVersion(),
                match.lostInputHash(), match.foundInputHash(), Timestamp.from(match.lostEmbeddingUpdatedAt()), Timestamp.from(match.foundEmbeddingUpdatedAt()),
                match.modelId(), match.modelRevision(), match.preprocessingVersion(), match.rankingVersion(),
                scores.semantic(), scores.location(), scores.date(), scores.category(), scores.finalScore(), scores.evidenceCoverage(),
                match.distanceMeters(), match.daysAfterLoss(), Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public int expireForReport(UUID reportId, Instant now) {
        return jdbc.update("""
                WITH affected AS (
                    SELECT id FROM report_match WHERE (lost_report_id = ? OR found_report_id = ?)
                        AND status IN ('PROPOSED', 'CONFIRMED') ORDER BY id FOR UPDATE
                )
                UPDATE report_match AS match SET status = 'EXPIRED', updated_at = ?, version = match.version + 1
                FROM affected WHERE match.id = affected.id
                """, reportId, reportId, Timestamp.from(now));
    }

    public int expireStaleForReport(UUID reportId, String rankingVersion, double minSemanticScore, Instant now) {
        return jdbc.update("""
                WITH stale AS (
                    SELECT m.id FROM report_match m
                    WHERE (m.lost_report_id = ? OR m.found_report_id = ?)
                        AND m.status IN ('PROPOSED', 'CONFIRMED') AND (
                            m.ranking_version <> ? OR m.semantic_score < ? OR NOT EXISTS (
                                SELECT 1 FROM report l
                                JOIN report f ON f.id = m.found_report_id
                                JOIN report_embedding le ON le.report_id = l.id
                                JOIN report_embedding fe ON fe.report_id = f.id
                                WHERE l.id = m.lost_report_id AND l.type = 'LOST' AND f.type = 'FOUND'
                                    AND l.status = 'ACTIVE' AND f.status = 'ACTIVE' AND l.author_id <> f.author_id
                                    AND l.version = m.lost_report_version AND f.version = m.found_report_version
                                    AND le.input_hash = m.lost_input_hash AND fe.input_hash = m.found_input_hash
                                    AND le.updated_at = m.lost_embedding_updated_at AND fe.updated_at = m.found_embedding_updated_at
                                    AND le.model_id = m.model_id AND fe.model_id = m.model_id
                                    AND le.model_revision = m.model_revision AND fe.model_revision = m.model_revision
                                    AND le.preprocessing_version = m.preprocessing_version AND fe.preprocessing_version = m.preprocessing_version
                            )
                        )
                    ORDER BY m.id FOR UPDATE OF m
                )
                UPDATE report_match m SET status = 'EXPIRED', updated_at = ?, version = m.version + 1
                FROM stale WHERE m.id = stale.id
                """, reportId, reportId, rankingVersion, minSemanticScore, Timestamp.from(now));
    }

    public record ReportState(UUID id, String type, String status, UUID authorId, long version) {}
    public record EmbeddingState(UUID reportId, String inputHash, String modelId, String modelRevision,
                                 String preprocessingVersion, Instant updatedAt) {}
}
