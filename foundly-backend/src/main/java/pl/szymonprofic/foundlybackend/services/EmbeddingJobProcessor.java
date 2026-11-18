package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.szymonprofic.foundlybackend.dto.ReportContent;
import pl.szymonprofic.foundlybackend.dto.ReportLocation;
import pl.szymonprofic.foundlybackend.models.ClaimedMatchingJob;
import pl.szymonprofic.foundlybackend.models.Report;
import pl.szymonprofic.foundlybackend.models.ReportEmbedding;
import pl.szymonprofic.foundlybackend.models.enums.ReportStatus;
import pl.szymonprofic.foundlybackend.repositories.ReportEmbeddingRepository;
import pl.szymonprofic.foundlybackend.repositories.ReportRepository;
import pl.szymonprofic.foundlybackend.config.MatchingRankingProperties;

import java.time.Clock;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmbeddingJobProcessor {
    private final ReportRepository reports;
    private final ReportEmbeddingRepository embeddings;
    private final EmbeddingTextBuilder textBuilder;
    private final MatchingJobService jobs;
    private final Clock clock;
    private final MatchingRankingService ranking;
    private final ReportMatchService matches;
    private final MatchingRankingProperties rankingPolicy;

    @Transactional(readOnly = true)
    public Optional<EmbeddingTextBuilder.PreparedText> prepare(ClaimedMatchingJob job) {
        return reports.findById(job.reportId()).filter(report -> isCurrent(report, job))
                .map(report -> textBuilder.build(report.getType(), new ReportContent(
                        report.getTitle(), report.getCategory().getCode(), report.getDescription(), report.getColor(),
                        report.getEventDate(), new ReportLocation(report.getLocationLabel(),
                        report.getLocationPoint().getX(), report.getLocationPoint().getY()))));
    }

    @Transactional
    public boolean completeIfCurrent(ClaimedMatchingJob job, EmbeddingTextBuilder.PreparedText prepared,
                                     EmbeddingClient.ModelInfo model) {
        if (!embeddings.isCurrent(job.reportId(), prepared.inputHash(), model.modelId(),
                model.modelRevision(), prepared.preprocessingVersion())) return false;
        matches.lockPublicationInputs(job.reportId());
        var report = reports.findForUpdate(job.reportId());
        if (report.isEmpty() || !isCurrent(report.get(), job)) {
            jobs.supersede(job);
            return true;
        }
        if (!embeddings.isCurrent(job.reportId(), prepared.inputHash(), model.modelId(),
                model.modelRevision(), prepared.preprocessingVersion())) return false;
        rankAndComplete(job);
        return true;
    }

    @Transactional
    public boolean publish(ClaimedMatchingJob job, EmbeddingTextBuilder.PreparedText prepared,
                           EmbeddingClient.EmbeddingResult result) {
        matches.lockPublicationInputs(job.reportId());
        var report = reports.findForUpdate(job.reportId());
        if (report.isEmpty() || !isCurrent(report.get(), job)) {
            jobs.supersede(job);
            return false;
        }
        embeddings.save(new ReportEmbedding(job.reportId(), result.numbers(), result.modelId(), result.modelRevision(),
                prepared.preprocessingVersion(), prepared.inputHash(), clock.instant()));
        rankAndComplete(job);
        return true;
    }

    private void rankAndComplete(ClaimedMatchingJob job) {
        matches.expireStaleForReport(job.reportId());
        for (var evaluation : ranking.rank(job.reportId(), rankingPolicy.maxResults())) {
            if (matches.saveIfCurrent(evaluation) == ReportMatchService.SaveOutcome.STALE)
                throw new IllegalStateException("Ranking inputs changed during publication");
        }
        if (!jobs.complete(job)) throw new LostLeaseException();
    }

    private boolean isCurrent(Report report, ClaimedMatchingJob job) {
        return report.getStatus() == ReportStatus.ACTIVE && report.getVersion() == job.reportVersion();
    }

    public static class LostLeaseException extends RuntimeException {
        public LostLeaseException() { super("Matching job lease was lost; result must be rolled back"); }
    }
}
