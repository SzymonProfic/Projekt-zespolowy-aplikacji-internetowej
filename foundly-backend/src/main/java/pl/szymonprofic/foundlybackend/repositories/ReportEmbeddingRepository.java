package pl.szymonprofic.foundlybackend.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.szymonprofic.foundlybackend.models.ReportEmbedding;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ReportEmbeddingRepository {
    private final JdbcTemplate jdbc;

    public Optional<ReportEmbedding> findByReportId(UUID reportId) {
        return jdbc.query("""
                SELECT report_id, embedding::text AS numbers, model_id, model_revision,
                       preprocessing_version, input_hash, updated_at
                FROM report_embedding WHERE report_id = ?
                """, (row, index) -> new ReportEmbedding(
                row.getObject("report_id", UUID.class), parseVector(row.getString("numbers")),
                row.getString("model_id"), row.getString("model_revision"),
                row.getString("preprocessing_version"), row.getString("input_hash"),
                row.getTimestamp("updated_at").toInstant()), reportId).stream().findFirst();
    }

    public void save(ReportEmbedding embedding) {
        String vector = embedding.numbers().stream()
                .map(value -> Double.toString(value)).collect(Collectors.joining(",", "[", "]"));
        jdbc.update("""
                INSERT INTO report_embedding
                    (report_id, embedding, model_id, model_revision, preprocessing_version, input_hash, updated_at)
                VALUES (?, CAST(? AS vector), ?, ?, ?, ?, ?)
                ON CONFLICT (report_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    model_id = EXCLUDED.model_id,
                    model_revision = EXCLUDED.model_revision,
                    preprocessing_version = EXCLUDED.preprocessing_version,
                    input_hash = EXCLUDED.input_hash,
                    updated_at = EXCLUDED.updated_at
                """, embedding.reportId(), vector, embedding.modelId(), embedding.modelRevision(),
                embedding.preprocessingVersion(), embedding.inputHash(), Timestamp.from(embedding.updatedAt()));
    }

    public boolean isCurrent(UUID reportId, String inputHash, String modelId, String modelRevision,
                             String preprocessingVersion) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM report_embedding
                    WHERE report_id = ? AND input_hash = ? AND model_id = ?
                        AND model_revision = ? AND preprocessing_version = ?
                )
                """, Boolean.class, reportId, inputHash, modelId, modelRevision, preprocessingVersion));
    }

    private static List<Double> parseVector(String vector) {
        return Arrays.stream(vector.substring(1, vector.length() - 1).split(","))
                .map(Double::parseDouble).toList();
    }
}
