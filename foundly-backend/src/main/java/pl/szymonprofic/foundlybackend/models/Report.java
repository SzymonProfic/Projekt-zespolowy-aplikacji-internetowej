package pl.szymonprofic.foundlybackend.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;
import pl.szymonprofic.foundlybackend.models.enums.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Report {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
    @Column(nullable = false, length = 150)
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReportType type;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ReportStatus status;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Column(length = 80)
    private String color;
    @Column(nullable = false, length = 255)
    private String locationLabel;
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(nullable = false, columnDefinition = "geography(Point,4326)")
    private Point locationPoint;
    @Column(nullable = false)
    private LocalDate eventDate;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    private Instant resolvedAt;
    @Version
    private long version;

    public Report(AppUser author, ReportType type, Instant now) {
        this.id = UUID.randomUUID();
        this.author = author;
        this.type = type;
        this.status = ReportStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void edit(Category category, String title, String description, String color,
                     String locationLabel, Point locationPoint, LocalDate eventDate, Instant now) {
        this.category = category;
        this.title = title;
        this.description = description;
        this.color = color;
        this.locationLabel = locationLabel;
        this.locationPoint = locationPoint;
        this.eventDate = eventDate;
        this.updatedAt = now;
    }

    public void resolve(Instant now) {
        if (status == ReportStatus.ACTIVE) {
            status = ReportStatus.RESOLVED;
            resolvedAt = now;
            updatedAt = now;
        }
    }
}
