package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.*;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import pl.szymonprofic.foundlybackend.repositories.specifications.ReportSpecifications;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.szymonprofic.foundlybackend.dto.*;
import pl.szymonprofic.foundlybackend.models.*;
import pl.szymonprofic.foundlybackend.models.enums.*;
import pl.szymonprofic.foundlybackend.repositories.*;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final ReportRepository reports;
    private final CategoryRepository categories;
    private final CurrentUserService users;
    private final Clock clock;
    private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);

    @Transactional(readOnly = true)
    public PageResponse<ReportResponse> list(ReportFilter filter, Jwt jwt, int page, int size) {
        validateFilter(filter);
        return search(filter, null, jwt, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportResponse> mine(ReportFilter filter, UUID authorId, Jwt jwt, int page, int size) {
        validateFilter(filter);
        return search(filter, authorId, jwt, page, size);
    }

    @Transactional(readOnly = true)
    public ReportResponse get(UUID id, Jwt jwt) {
        return response(find(id), jwt, true);
    }

    @Transactional
    public ReportResponse create(CreateReportRequest request, AppUser author, Jwt jwt) {
        var report = new Report(author, ReportType.valueOf(request.type().toUpperCase(Locale.ROOT)), clock.instant());
        apply(report, request.content());
        Report persisted = reports.saveAndFlush(report);
        return response(persisted, jwt, true);
    }

    @Transactional
    public ReportResponse update(UUID id, UpdateReportRequest request, Jwt jwt) {
        Report report = owned(id, jwt);
        checkVersion(report, request.version());
        if (report.getStatus() != ReportStatus.ACTIVE)
            throw error(HttpStatus.CONFLICT, "Zakończonego zgłoszenia nie można edytować");
        apply(report, request.content());
        reports.flush();
        return response(report, jwt, true);
    }

    @Transactional
    public ReportResponse resolve(UUID id, ResolveReportRequest request, Jwt jwt) {
        Report report = owned(id, jwt);
        // A repeated resolution is safe even when the caller still holds the pre-resolution version.
        if (report.getStatus() == ReportStatus.RESOLVED) return response(report, jwt, true);
        checkVersion(report, request.version());
        report.resolve(clock.instant());
        reports.flush();
        return response(report, jwt, true);
    }

    @Transactional
    public void delete(UUID id, long version, Jwt jwt) {
        Report report = owned(id, jwt);
        checkVersion(report, version);
        reports.delete(report);
        reports.flush();
    }

    private PageResponse<ReportResponse> search(ReportFilter filter, UUID authorId, Jwt jwt, int page, int size) {
        var specification = ReportSpecifications.withFilters(filter);
        if (authorId != null) specification = specification.and(ReportSpecifications.authoredBy(authorId));
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        if (filter.radiusMeters() != null) {
            specification = specification.and(ReportSpecifications.nearestFirst(filter.longitude(), filter.latitude()));
            // Pageable property sorting cannot express a spatial distance; Criteria defines that order.
            sort = Sort.unsorted();
        }
        var result = reports.findAll(specification, PageRequest.of(page, size, sort));
        return PageResponse.of(result.getContent().stream().map(r -> response(r, jwt, false)).toList(),
                page, size, result.getTotalElements());
    }

    private Report find(UUID id) {
        return reports.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Nie znaleziono zgłoszenia"));
    }

    private Report owned(UUID id, Jwt jwt) {
        Report report = find(id);
        if (!users.isAuthor(report.getAuthor(), jwt))
            throw error(HttpStatus.FORBIDDEN, "Zgłoszenie należy do innego użytkownika");
        return report;
    }

    private ReportResponse response(Report r, Jwt jwt, boolean details) {
        boolean owner = users.isAuthor(r.getAuthor(), jwt);
        var location = owner && details ? new ReportLocation(r.getLocationLabel(),
                r.getLocationPoint().getX(), r.getLocationPoint().getY()) : null;
        return new ReportResponse(r.getId(), r.getTitle(), r.getType().name().toLowerCase(Locale.ROOT),
                r.getCategory().getName(), r.getCategory().getCode(), r.getLocationLabel(), r.getEventDate(),
                r.getColor() == null ? "" : r.getColor(), r.getDescription(), owner,
                r.getStatus() == ReportStatus.RESOLVED, r.getStatus().name(), r.getVersion(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getResolvedAt(), location);
    }

    private void apply(Report report, ReportContent content) {
        if (content.eventDate().isAfter(LocalDate.now(clock)))
            throw error(HttpStatus.BAD_REQUEST, "Data zdarzenia nie może być przyszła");
        var location = content.location();
        if (!Double.isFinite(location.longitude()) || !Double.isFinite(location.latitude()))
            throw error(HttpStatus.BAD_REQUEST, "Współrzędne muszą być skończonymi liczbami");
        Category category = categories.findByCode(content.categoryCode())
                .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "Nieznana kategoria"));
        if (!category.isActive() && (report.getCategory() == null || !category.getId().equals(report.getCategory().getId())))
            throw error(HttpStatus.BAD_REQUEST, "Kategoria jest nieaktywna");
        report.edit(category, content.title(), content.description(), content.color(), location.label(),
                GEOMETRY.createPoint(new Coordinate(location.longitude(), location.latitude())), content.eventDate(), clock.instant());
    }

    private void validateFilter(ReportFilter f) {
        int provided = (f.longitude() == null ? 0 : 1) + (f.latitude() == null ? 0 : 1) + (f.radiusMeters() == null ? 0 : 1);
        if (provided != 0 && provided != 3)
            throw error(HttpStatus.BAD_REQUEST, "Podaj longitude, latitude i radiusMeters razem");
        if (provided == 3 && (!Double.isFinite(f.longitude()) || !Double.isFinite(f.latitude()) || !Double.isFinite(f.radiusMeters())))
            throw error(HttpStatus.BAD_REQUEST, "Parametry przestrzenne muszą być skończonymi liczbami");
        if (f.dateFrom() != null && f.dateTo() != null && f.dateFrom().isAfter(f.dateTo()))
            throw error(HttpStatus.BAD_REQUEST, "dateFrom nie może być późniejsze niż dateTo");
    }

    private void checkVersion(Report report, long version) {
        if (report.getVersion() != version)
            throw error(HttpStatus.CONFLICT, "Zgłoszenie zostało zmienione; pobierz aktualną wersję");
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
