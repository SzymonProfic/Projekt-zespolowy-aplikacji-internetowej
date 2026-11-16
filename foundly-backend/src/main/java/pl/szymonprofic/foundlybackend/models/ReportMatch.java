package pl.szymonprofic.foundlybackend.models;

import java.time.Instant;
import java.util.UUID;

public record ReportMatch(UUID id, UUID lostReportId, UUID foundReportId,
                          long lostReportVersion, long foundReportVersion, String lostInputHash, String foundInputHash,
                          Instant lostEmbeddingUpdatedAt, Instant foundEmbeddingUpdatedAt,
                          String modelId, String modelRevision, String preprocessingVersion, String rankingVersion,
                          double semanticScore, Double locationScore, Double dateScore, Double categoryScore,
                          double finalScore, double evidenceCoverage, Double distanceMeters, Long daysAfterLoss,
                          ReportMatchStatus status, Instant createdAt, Instant updatedAt, long version) {}
