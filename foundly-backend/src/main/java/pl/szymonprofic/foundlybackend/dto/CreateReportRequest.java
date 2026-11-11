package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record CreateReportRequest(@NotNull @Pattern(regexp = "lost|found") String type,
                                  @NotBlank @Size(min = 3, max = 150) String title,
                                  @NotBlank @Size(max = 40) String categoryCode,
                                  @NotBlank @Size(min = 10, max = 5000) String description,
                                  @Size(max = 80) String color, @NotNull LocalDate eventDate,
                                  @NotNull @Valid ReportLocation location) {
    public CreateReportRequest {
        var normalized = new ReportContent(title, categoryCode, description, color, eventDate, location);
        title = normalized.title(); categoryCode = normalized.categoryCode();
        description = normalized.description(); color = normalized.color();
    }

    public ReportContent content() {
        return new ReportContent(title, categoryCode, description, color, eventDate, location);
    }
}
