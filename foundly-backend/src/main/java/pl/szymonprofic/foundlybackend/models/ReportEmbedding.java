package pl.szymonprofic.foundlybackend.models;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ReportEmbedding(UUID reportId, List<Double> numbers, String modelId, String modelRevision,
                              String preprocessingVersion, String inputHash, Instant updatedAt) {
    public ReportEmbedding {
        Objects.requireNonNull(reportId, "reportId");
        numbers = List.copyOf(numbers);
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelRevision, "modelRevision");
        Objects.requireNonNull(preprocessingVersion, "preprocessingVersion");
        Objects.requireNonNull(inputHash, "inputHash");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
