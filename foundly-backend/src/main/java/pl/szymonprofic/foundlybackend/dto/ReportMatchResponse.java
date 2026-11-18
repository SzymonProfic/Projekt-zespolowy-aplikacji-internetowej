package pl.szymonprofic.foundlybackend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReportMatchResponse(UUID id, String status, double finalScore, Scores scores,
                                  Long approximateDistanceMeters, Long daysAfterLoss,
                                  Instant createdAt, Instant updatedAt, MatchedReport report) {
    public record Scores(double semantic, Double location, Double date, Double category, double evidenceCoverage) {}
    public record MatchedReport(UUID id, String title, String type, String categoryCode, String category,
                                String location, LocalDate date, String color) {}
}
