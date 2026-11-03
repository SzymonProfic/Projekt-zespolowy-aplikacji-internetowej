package pl.szymonprofic.foundlybackend.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(name = "uq_app_user_identity", columnNames = {"auth_issuer", "auth_subject"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AppUser {
    @Id
    private UUID id;
    @Column(nullable = false, length = 512)
    private String authIssuer;
    @Column(nullable = false, length = 255)
    private String authSubject;
    @Column(nullable = false, length = 100)
    private String displayName;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(length = 254)
    private String email;
    @Column(nullable = false)
    private boolean emailVerified;
    @Column(nullable = false)
    private Instant updatedAt;
    private Instant identityClaimsIssuedAt;

    public AppUser(String issuer, String subject, String displayName, String email,
                   boolean emailVerified, Instant issuedAt, Instant now) {
        this.id = UUID.randomUUID();
        this.authIssuer = issuer;
        this.authSubject = subject;
        this.createdAt = now;
        applyIdentity(displayName, email, emailVerified, issuedAt, now);
    }

    public void synchronizeIdentity(String displayName, String email, boolean emailVerified,
                                    Instant issuedAt, Instant now) {
        // NumericDate has second precision. For equal timestamps the first accepted token wins.
        if (identityClaimsIssuedAt != null && !issuedAt.isAfter(identityClaimsIssuedAt)) return;
        applyIdentity(displayName, email, emailVerified, issuedAt, now);
    }

    private void applyIdentity(String displayName, String email, boolean emailVerified,
                               Instant issuedAt, Instant now) {
        this.displayName = displayName;
        this.email = email;
        this.emailVerified = email != null && emailVerified;
        this.identityClaimsIssuedAt = issuedAt;
        this.updatedAt = now;
    }
}
