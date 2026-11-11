package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.constraints.*;

public record ReportLocation(@NotBlank @Size(min = 3, max = 255) String label,
                             @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                             @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude) {
    public ReportLocation { if (label != null) label = label.strip(); }
}
