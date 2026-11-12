package pl.szymonprofic.foundlybackend.dto;

import java.time.*;
import java.util.UUID;

public record ReportResponse(UUID id, String title, String type, String category, String categoryCode,
                             String location, LocalDate date, String color, String description,
                             boolean owner, boolean recovered, String status, long version,
                             Instant createdAt, Instant updatedAt, Instant resolvedAt,
                             ReportLocation editableLocation, ReportLocationPoint locationPoint) {}
