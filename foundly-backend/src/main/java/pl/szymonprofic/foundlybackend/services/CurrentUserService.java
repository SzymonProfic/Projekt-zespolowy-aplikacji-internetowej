package pl.szymonprofic.foundlybackend.services;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.szymonprofic.foundlybackend.models.AppUser;
import pl.szymonprofic.foundlybackend.repositories.AppUserRepository;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class CurrentUserService {
    private final AppUserRepository users;
    private final ProfileSynchronizationService synchronization;
    private final Validator validator;

    public ProfileSynchronizationService.Result synchronize(Jwt jwt) {
        var identity = identity(jwt);
        try {
            return synchronization.synchronize(identity);
        } catch (DataIntegrityViolationException exception) {
            // The failed insert has already rolled back. Retry the identity race in a new transaction.
            if (!isIdentityConflict(exception)) throw exception;
            return synchronization.synchronize(identity);
        }
    }

    public AppUser require(Jwt jwt) { return synchronize(jwt).user(); }

    @Transactional(readOnly = true)
    public AppUser get(Jwt jwt) {
        return users.findByAuthIssuerAndAuthSubject(jwt.getIssuer().toString(), jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Profil nie istnieje; wywołaj PUT /api/me po zalogowaniu"));
    }

    public boolean isAuthor(AppUser author, Jwt jwt) {
        return jwt != null && author.getAuthIssuer().equals(jwt.getIssuer().toString())
                && author.getAuthSubject().equals(jwt.getSubject());
    }

    private IdentityProfile identity(Jwt jwt) {
        String name = jwt.getClaimAsString("preferred_username");
        name = name == null || name.isBlank() ? "Użytkownik" : name.strip();
        name = name.substring(0, Math.min(100, name.length()));
        String email = jwt.getClaimAsString("email");
        email = email == null || email.isBlank() ? null : email.strip();
        if (jwt.getIssuedAt() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token nie zawiera iat");
        var identity = new IdentityProfile(jwt.getIssuer().toString(), jwt.getSubject(), name, email,
                email != null && Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                jwt.getIssuedAt().truncatedTo(ChronoUnit.SECONDS));
        if (!validator.validate(identity).isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Niepoprawne dane profilu w tokenie Keycloak");
        return identity;
    }

    private boolean isIdentityConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "uq_app_user_identity".equals(constraint.getConstraintName())) return true;
        }
        return false;
    }
}
