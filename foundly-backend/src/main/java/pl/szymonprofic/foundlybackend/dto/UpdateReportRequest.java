package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record UpdateReportRequest(@NotNull @PositiveOrZero Long version,
                                  @NotNull @Valid ReportContent content) {}
