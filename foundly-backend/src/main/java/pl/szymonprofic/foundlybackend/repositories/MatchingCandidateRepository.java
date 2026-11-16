package pl.szymonprofic.foundlybackend.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.szymonprofic.foundlybackend.models.enums.ReportType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MatchingCandidateRepository {
    private final JdbcTemplate jdbc;

    // One statement gives a consistent snapshot of both reports and both embeddings.
    // Do not LIMIT by semantic similarity: hybrid scoring and freshness checks come first.
    public List<Candidate> findCandidates(UUID reportId) {
        return jdbc.query("""
                SELECT s.id AS s_id, s.version AS s_version, s.type AS s_type, s.title AS s_title,
                       s.description AS s_description, s.color AS s_color,
                       se.input_hash AS s_hash, se.preprocessing_version AS s_preprocessing,
                       se.updated_at AS s_embedding_updated_at,
                       c.id AS c_id, c.version AS c_version, c.type AS c_type, c.title AS c_title,
                       c.description AS c_description, c.color AS c_color,
                       ce.input_hash AS c_hash, ce.preprocessing_version AS c_preprocessing,
                       ce.updated_at AS c_embedding_updated_at,
                       se.model_id, se.model_revision,
                       1 - (se.embedding <=> ce.embedding) AS semantic_similarity,
                       ST_Distance(s.location_point, c.location_point) AS distance_meters,
                       CASE WHEN s.type = 'LOST' THEN s.event_date ELSE c.event_date END AS lost_date,
                       CASE WHEN s.type = 'FOUND' THEN s.event_date ELSE c.event_date END AS found_date,
                       sc.code AS source_category, cc.code AS candidate_category
                FROM report s
                JOIN report_embedding se ON se.report_id = s.id
                JOIN category sc ON sc.id = s.category_id
                JOIN report c ON c.type <> s.type AND c.author_id <> s.author_id AND c.status = 'ACTIVE'
                JOIN category cc ON cc.id = c.category_id
                JOIN report_embedding ce ON ce.report_id = c.id
                    AND ce.model_id = se.model_id AND ce.model_revision = se.model_revision
                    AND ce.preprocessing_version = se.preprocessing_version
                WHERE s.id = ? AND s.status = 'ACTIVE'
                    AND NOT EXISTS (
                        SELECT 1 FROM report_match rejected
                        WHERE rejected.status = 'REJECTED'
                            AND rejected.lost_report_id = CASE WHEN s.type = 'LOST' THEN s.id ELSE c.id END
                            AND rejected.found_report_id = CASE WHEN s.type = 'FOUND' THEN s.id ELSE c.id END
                    )
                ORDER BY c.id
                """, (row, index) -> new Candidate(snapshot(row, "s_"), snapshot(row, "c_"),
                row.getString("model_id"), row.getString("model_revision"), row.getDouble("semantic_similarity"),
                row.getObject("distance_meters", Double.class), row.getObject("lost_date", LocalDate.class),
                row.getObject("found_date", LocalDate.class), row.getString("source_category"),
                row.getString("candidate_category")), reportId);
    }

    private ReportSnapshot snapshot(ResultSet row, String prefix) throws SQLException {
        return new ReportSnapshot(row.getObject(prefix + "id", UUID.class), row.getLong(prefix + "version"),
                ReportType.valueOf(row.getString(prefix + "type")), row.getString(prefix + "title"),
                row.getString(prefix + "description"), row.getString(prefix + "color"),
                row.getString(prefix + "hash"), row.getString(prefix + "preprocessing"),
                row.getTimestamp(prefix + "embedding_updated_at").toInstant());
    }

    public record ReportSnapshot(UUID id, long version, ReportType type, String title, String description,
                                 String color, String inputHash, String preprocessingVersion, Instant embeddingUpdatedAt) {}

    public record Candidate(ReportSnapshot source, ReportSnapshot candidate, String modelId, String modelRevision,
                            double semanticSimilarity, Double distanceMeters, LocalDate lostDate,
                            LocalDate foundDate, String sourceCategory, String candidateCategory) {}
}
