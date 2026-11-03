package pl.szymonprofic.foundlybackend.repositories.specifications;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.domain.Specification;
import pl.szymonprofic.foundlybackend.dto.ReportFilter;
import pl.szymonprofic.foundlybackend.models.Report;
import pl.szymonprofic.foundlybackend.models.enums.ReportStatus;
import pl.szymonprofic.foundlybackend.models.enums.ReportType;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public final class ReportSpecifications {
    private ReportSpecifications() {}

    public static Specification<Report> withFilters(ReportFilter filter) {
        return (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            String status = filter.status() == null ? "ACTIVE" : filter.status();
            if (!"ALL".equals(status)) {
                predicates.add(builder.equal(root.get("status"), ReportStatus.valueOf(status)));
            }
            if (filter.type() != null) {
                predicates.add(builder.equal(root.get("type"), ReportType.valueOf(filter.type().toUpperCase(Locale.ROOT))));
            }
            if (filter.categoryCode() != null) {
                predicates.add(builder.equal(root.get("category").get("code"), filter.categoryCode()));
            }
            if (filter.dateFrom() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), filter.dateTo()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String pattern = "%" + escapeLike(filter.q().strip().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern, '!'),
                        builder.like(builder.lower(root.get("description")), pattern, '!')));
            }
            if (filter.radiusMeters() != null) {
                predicates.add(builder.isTrue(builder.function("st_dwithin", Boolean.class,
                        root.get("locationPoint"), geographyPoint(builder, filter.longitude(), filter.latitude()),
                        builder.literal(filter.radiusMeters()))));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Report> authoredBy(UUID authorId) {
        return (root, query, builder) -> builder.equal(root.get("author").get("id"), authorId);
    }

    public static Specification<Report> nearestFirst(double longitude, double latitude) {
        return (root, query, builder) -> {
            // Spring Data reuses the specification for COUNT; ordering applies only to report rows.
            if (query != null && !Long.class.equals(query.getResultType())) {
                var distance = builder.function("st_distance", Double.class, root.get("locationPoint"),
                        geographyPoint(builder, longitude, latitude));
                query.orderBy(builder.asc(distance), builder.asc(root.get("id")));
            }
            return builder.conjunction();
        };
    }

    private static Expression<Point> geographyPoint(CriteriaBuilder builder, double longitude, double latitude) {
        // This SQL function returns geography, so PostGIS evaluates distances in meters.
        // The indexed locationPoint column is used directly, without a cast to geometry.
        String point = "SRID=4326;POINT(" + longitude + " " + latitude + ")";
        return builder.function("st_geogfromtext", Point.class, builder.literal(point));
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
