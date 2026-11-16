package pl.szymonprofic.foundlybackend.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.szymonprofic.foundlybackend.dto.PageResponse;
import pl.szymonprofic.foundlybackend.dto.ReportMatchResponse;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReportMatchReadRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // The same predicate is used for the page and its total count.
    private static final String CURRENT_MATCHES = """
            FROM report_match m
            JOIN report l ON l.id = m.lost_report_id
            JOIN report f ON f.id = m.found_report_id
            JOIN report_embedding le ON le.report_id = l.id
            JOIN report_embedding fe ON fe.report_id = f.id
            JOIN report r ON r.id = CASE WHEN m.lost_report_id = :reportId THEN f.id ELSE l.id END
            JOIN category c ON c.id = r.category_id
            WHERE (m.lost_report_id = :reportId OR m.found_report_id = :reportId)
                AND m.status IN ('PROPOSED', 'CONFIRMED')
                AND l.status = 'ACTIVE' AND f.status = 'ACTIVE' AND l.type = 'LOST' AND f.type = 'FOUND'
                AND l.author_id <> f.author_id
                AND l.version = m.lost_report_version AND f.version = m.found_report_version
                AND le.input_hash = m.lost_input_hash AND fe.input_hash = m.found_input_hash
                AND le.updated_at = m.lost_embedding_updated_at AND fe.updated_at = m.found_embedding_updated_at
                AND le.model_id = m.model_id AND fe.model_id = m.model_id
                AND le.model_revision = m.model_revision AND fe.model_revision = m.model_revision
                AND le.preprocessing_version = m.preprocessing_version AND fe.preprocessing_version = m.preprocessing_version
                AND m.preprocessing_version = :preprocessingVersion AND m.ranking_version = :rankingVersion
                AND m.semantic_score >= :minSemanticScore
                AND CASE WHEN m.lost_report_id = :reportId THEN le.input_hash ELSE fe.input_hash END = :inputHash
            """;

    public PageResponse<ReportMatchResponse> findCurrent(UUID reportId, String inputHash, String preprocessingVersion,
                                                        String rankingVersion, double minSemanticScore, int page, int size) {
        var parameters = new MapSqlParameterSource().addValue("reportId", reportId).addValue("inputHash", inputHash)
                .addValue("preprocessingVersion", preprocessingVersion).addValue("rankingVersion", rankingVersion)
                .addValue("minSemanticScore", minSemanticScore).addValue("size", size).addValue("offset", (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) " + CURRENT_MATCHES, parameters, Long.class);
        var content = jdbc.query("""
                SELECT m.id, m.status, m.final_score, m.semantic_score, m.location_score, m.date_score,
                    m.category_score, m.evidence_coverage, m.distance_meters, m.days_after_loss, m.created_at, m.updated_at,
                    r.id AS report_id, r.title, r.type, c.code AS category_code, c.name AS category_name,
                    r.location_label, r.event_date, r.color
                """ + CURRENT_MATCHES + " ORDER BY m.final_score DESC, m.id LIMIT :size OFFSET :offset", parameters,
                (row, index) -> {
                    Double distance = row.getObject("distance_meters", Double.class);
                    return new ReportMatchResponse(row.getObject("id", UUID.class), row.getString("status"),
                            rounded(row.getDouble("final_score")), new ReportMatchResponse.Scores(
                            rounded(row.getDouble("semantic_score")), rounded(row.getObject("location_score", Double.class)),
                            rounded(row.getObject("date_score", Double.class)), rounded(row.getObject("category_score", Double.class)),
                            rounded(row.getDouble("evidence_coverage"))), distance == null ? null : Math.round(distance / 1000.0),
                            row.getObject("days_after_loss", Long.class), row.getTimestamp("created_at").toInstant(),
                            row.getTimestamp("updated_at").toInstant(), new ReportMatchResponse.MatchedReport(
                            row.getObject("report_id", UUID.class), row.getString("title"), row.getString("type").toLowerCase(Locale.ROOT),
                            row.getString("category_code"), row.getString("category_name"), row.getString("location_label"),
                            row.getObject("event_date", LocalDate.class), row.getString("color")));
                });
        return PageResponse.of(content, page, size, total);
    }

    private static Double rounded(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }
}
