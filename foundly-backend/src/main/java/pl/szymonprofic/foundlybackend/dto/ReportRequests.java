package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class ReportRequests {
    private ReportRequests() {}

    public record Location(@NotBlank @Size(min = 3, max = 255) String label,
                           @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                           @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude) {
        public Location { if (label != null) label = label.strip(); }
    }

    public record Content(@NotBlank @Size(min = 3, max = 150) String title,
                          @NotBlank @Size(max = 40) String categoryCode,
                          @NotBlank @Size(min = 10, max = 5000) String description,
                          @Size(max = 80) String color,
                          @NotNull LocalDate eventDate,
                          @NotNull @Valid Location location) {
        public Content {
            if (title != null) title = title.strip();
            if (description != null) description = description.strip();
            if (categoryCode != null) categoryCode = categoryCode.strip();
            if (color != null) { color = color.strip(); if (color.isEmpty()) color = null; }
        }
    }

    public record Create(@NotNull @Pattern(regexp = "lost|found") String type,
                         @NotBlank @Size(min = 3, max = 150) String title,
                         @NotBlank @Size(max = 40) String categoryCode,
                         @NotBlank @Size(min = 10, max = 5000) String description,
                         @Size(max = 80) String color, @NotNull LocalDate eventDate,
                         @NotNull @Valid Location location) {
        public Create {
            var normalized = new Content(title, categoryCode, description, color, eventDate, location);
            title = normalized.title(); categoryCode = normalized.categoryCode();
            description = normalized.description(); color = normalized.color();
        }
        public Content content() { return new Content(title, categoryCode, description, color, eventDate, location); }
    }
    public record Update(@NotNull @PositiveOrZero Long version, @NotNull @Valid Content content) {}
    public record Resolve(@NotNull @PositiveOrZero Long version,
                          @NotNull @Pattern(regexp = "RESOLVED") String status) {}
}
