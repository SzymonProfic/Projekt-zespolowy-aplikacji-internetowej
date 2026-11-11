package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ReportContent(@NotBlank @Size(min = 3, max = 150) String title,
                            @NotBlank @Size(max = 40) String categoryCode,
                            @NotBlank @Size(min = 10, max = 5000) String description,
                            @Size(max = 80) String color,
                            @NotNull LocalDate eventDate,
                            @NotNull @Valid ReportLocation location) {
    public ReportContent {
        if (title != null) title = title.strip();
        if (description != null) description = description.strip();
        if (categoryCode != null) categoryCode = categoryCode.strip();
        if (color != null) { color = color.strip(); if (color.isEmpty()) color = null; }
    }
}
