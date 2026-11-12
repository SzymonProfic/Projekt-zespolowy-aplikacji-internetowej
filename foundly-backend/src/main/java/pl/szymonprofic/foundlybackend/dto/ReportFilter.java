package pl.szymonprofic.foundlybackend.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ReportFilter(@Pattern(regexp = "lost|found") String type,
                           @Size(max = 40) String categoryCode,
                           @Pattern(regexp = "ACTIVE|RESOLVED|ALL") String status,
                           LocalDate dateFrom, LocalDate dateTo,
                           @Size(max = 200) String q,
                           @Size(max = 255) String location,
                           @DecimalMin("-180") @DecimalMax("180") Double longitude,
                           @DecimalMin("-90") @DecimalMax("90") Double latitude,
                           @DecimalMin("1") @DecimalMax("100000") Double radiusMeters) {}
