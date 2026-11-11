package pl.szymonprofic.foundlybackend.controllers;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springdoc.core.annotations.ParameterObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pl.szymonprofic.foundlybackend.dto.*;
import pl.szymonprofic.foundlybackend.services.ReportService;
import pl.szymonprofic.foundlybackend.services.CurrentUserService;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reports;
    private final CurrentUserService users;

    @GetMapping
    public PageResponse<ReportResponse> list(@ParameterObject @Valid @ModelAttribute ReportFilter filter,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return reports.list(filter, jwt, page, size);
    }

    @GetMapping("/mine")
    public PageResponse<ReportResponse> mine(@ParameterObject @Valid @ModelAttribute ReportFilter filter,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return reports.mine(filter, users.require(jwt).getId(), jwt, page, size);
    }

    @GetMapping("/{id}")
    public ReportResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reports.get(id, jwt);
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Zgłoszenie utworzone")
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateReportRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        var report = reports.create(request, users.require(jwt), jwt);
        return ResponseEntity.created(URI.create("/api/reports/" + report.id())).body(report);
    }

    @PutMapping("/{id}")
    public ReportResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateReportRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return reports.update(id, request, jwt);
    }

    @PatchMapping("/{id}/status")
    public ReportResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveReportRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return reports.resolve(id, request, jwt);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "Zgłoszenie usunięte")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam @Min(0) long version,
                                       @AuthenticationPrincipal Jwt jwt) {
        reports.delete(id, version, jwt);
        return ResponseEntity.noContent().build();
    }

}
