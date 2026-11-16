package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.szymonprofic.foundlybackend.config.MatchingRankingProperties;
import pl.szymonprofic.foundlybackend.models.enums.ReportType;
import pl.szymonprofic.foundlybackend.repositories.MatchingCandidateRepository;
import pl.szymonprofic.foundlybackend.repositories.MatchingCandidateRepository.ReportSnapshot;

import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MatchingRankingService {
    private final MatchingCandidateRepository candidates;
    private final EmbeddingTextBuilder textBuilder;
    private final MatchingScoreCalculator calculator;
    private final MatchingRankingProperties policy;

    @Transactional(readOnly = true)
    public List<RankedMatch> rank(UUID reportId, int limit) {
        Objects.requireNonNull(reportId, "reportId");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Ranking limit must be between 1 and 100");
        var rows = candidates.findCandidates(reportId);
        if (rows.isEmpty() || !hasCurrentEmbedding(rows.getFirst().source())) return List.of();
        return rows.stream()
                .filter(row -> Double.isFinite(row.semanticSimilarity()))
                .filter(row -> Math.clamp(row.semanticSimilarity(), 0, 1) >= policy.minSemanticScore())
                .filter(row -> hasCurrentEmbedding(row.candidate()))
                .map(row -> {
                    var lost = row.source().type() == ReportType.LOST ? row.source() : row.candidate();
                    var found = row.source().type() == ReportType.FOUND ? row.source() : row.candidate();
                    Long days = row.lostDate() == null || row.foundDate() == null ? null
                            : ChronoUnit.DAYS.between(row.lostDate(), row.foundDate());
                    return new RankedMatch(lost.id(), found.id(), lost.version(), found.version(), row.modelId(),
                            row.modelRevision(), row.source().preprocessingVersion(), policy.version(),
                            lost.inputHash(), found.inputHash(), lost.embeddingUpdatedAt(), found.embeddingUpdatedAt(),
                            row.distanceMeters(), days, calculator.calculate(row.semanticSimilarity(), row.distanceMeters(),
                            row.lostDate(), row.foundDate(), row.sourceCategory(), row.candidateCategory()));
                })
                .sorted(Comparator.comparingDouble((RankedMatch match) -> match.scores().finalScore()).reversed()
                        .thenComparing(Comparator.comparingDouble((RankedMatch match) -> match.scores().semantic()).reversed())
                        .thenComparing(match -> match.lostReportId().toString())
                        .thenComparing(match -> match.foundReportId().toString()))
                .limit(limit).toList();
    }

    private boolean hasCurrentEmbedding(ReportSnapshot report) {
        var prepared = textBuilder.build(report.type(), report.title(), report.description(), report.color());
        return prepared.inputHash().equals(report.inputHash())
                && prepared.preprocessingVersion().equals(report.preprocessingVersion());
    }

    public record RankedMatch(UUID lostReportId, UUID foundReportId, long lostReportVersion, long foundReportVersion,
                              String modelId, String modelRevision, String preprocessingVersion, String rankingVersion,
                              String lostInputHash, String foundInputHash, Instant lostEmbeddingUpdatedAt, Instant foundEmbeddingUpdatedAt,
                              Double distanceMeters, Long daysAfterLoss, MatchingScoreCalculator.Scores scores) {}
}
