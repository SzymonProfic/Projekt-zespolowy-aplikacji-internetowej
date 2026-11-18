package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import pl.szymonprofic.foundlybackend.config.MatchingRankingProperties;
import pl.szymonprofic.foundlybackend.models.ReportMatchStatus;
import pl.szymonprofic.foundlybackend.repositories.ReportMatchRepository;
import pl.szymonprofic.foundlybackend.repositories.ReportMatchReadRepository;
import pl.szymonprofic.foundlybackend.repositories.ReportRepository;
import pl.szymonprofic.foundlybackend.dto.PageResponse;
import pl.szymonprofic.foundlybackend.dto.ReportMatchResponse;
import pl.szymonprofic.foundlybackend.repositories.ReportMatchRepository.EmbeddingState;
import pl.szymonprofic.foundlybackend.services.MatchingRankingService.RankedMatch;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportMatchService {
    private final ReportMatchRepository matches;
    private final MatchingRankingProperties policy;
    private final Clock clock;
    private final ReportRepository reports;
    private final ReportMatchReadRepository matchReads;
    private final CurrentUserService users;
    private final EmbeddingTextBuilder textBuilder;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ReportMatchResponse> list(UUID reportId, Jwt jwt, int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa paginacja");
        var report = reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono zgłoszenia"));
        if (!users.isAuthor(report.getAuthor(), jwt))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Zgłoszenie należy do innego użytkownika");
        var prepared = textBuilder.build(report.getType(), report.getTitle(), report.getDescription(), report.getColor());
        return matchReads.findCurrent(reportId, prepared.inputHash(), prepared.preprocessingVersion(),
                policy.version(), policy.minSemanticScore(), page, size);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPublicationInputs(UUID reportId) {
        matches.lockPublicationInputs(reportId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expireStaleForReport(UUID reportId) {
        matches.expireStaleForReport(reportId, policy.version(), policy.minSemanticScore(), clock.instant());
    }

    @Transactional
    public SaveOutcome saveIfCurrent(RankedMatch match) {
        Objects.requireNonNull(match, "match");
        if (match.lostReportId().equals(match.foundReportId()) || !policy.version().equals(match.rankingVersion())
                || !Double.isFinite(match.scores().semantic()) || match.scores().semantic() < policy.minSemanticScore())
            return SaveOutcome.STALE;
        var reports = matches.lockReports(match.lostReportId(), match.foundReportId());
        if (reports.size() != 2) return SaveOutcome.STALE;
        var lost = reports.stream().filter(row -> row.id().equals(match.lostReportId())).findFirst().orElseThrow();
        var found = reports.stream().filter(row -> row.id().equals(match.foundReportId())).findFirst().orElseThrow();
        if (!"LOST".equals(lost.type()) || !"FOUND".equals(found.type()) || lost.authorId().equals(found.authorId())
                || !"ACTIVE".equals(lost.status()) || !"ACTIVE".equals(found.status())
                || lost.version() != match.lostReportVersion() || found.version() != match.foundReportVersion())
            return SaveOutcome.STALE;
        var embeddings = matches.lockEmbeddings(lost.id(), found.id());
        if (embeddings.size() != 2 || embeddings.stream().anyMatch(embedding -> !isCurrent(embedding, match)))
            return SaveOutcome.STALE;
        var stored = matches.findByPairForUpdate(lost.id(), found.id());
        if (stored.isPresent() && (stored.get().status() == ReportMatchStatus.REJECTED
                || stored.get().status() == ReportMatchStatus.CONFIRMED)) return SaveOutcome.PROTECTED;
        return matches.upsert(match, clock.instant()) ? SaveOutcome.SAVED : SaveOutcome.UNCHANGED;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expireForReport(UUID reportId) {
        matches.expireForReport(reportId, clock.instant());
    }

    private boolean isCurrent(EmbeddingState embedding, RankedMatch match) {
        boolean lost = embedding.reportId().equals(match.lostReportId());
        String hash = lost ? match.lostInputHash() : match.foundInputHash();
        Instant updatedAt = lost ? match.lostEmbeddingUpdatedAt() : match.foundEmbeddingUpdatedAt();
        return embedding.inputHash().equals(hash) && embedding.updatedAt().equals(updatedAt)
                && embedding.modelId().equals(match.modelId()) && embedding.modelRevision().equals(match.modelRevision())
                && embedding.preprocessingVersion().equals(match.preprocessingVersion());
    }

    public enum SaveOutcome { SAVED, UNCHANGED, STALE, PROTECTED }
}
