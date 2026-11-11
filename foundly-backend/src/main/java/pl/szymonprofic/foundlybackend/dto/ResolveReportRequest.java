package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.constraints.*;

public record ResolveReportRequest(@NotNull @PositiveOrZero Long version,
                                   @NotNull @Pattern(regexp = "RESOLVED") String status) {}
