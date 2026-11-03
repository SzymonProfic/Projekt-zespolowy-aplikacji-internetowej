package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.szymonprofic.foundlybackend.models.AppUser;
import pl.szymonprofic.foundlybackend.repositories.AppUserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class ProfileSynchronizationService {
    private final AppUserRepository users;
    private final Clock clock;

    public record Result(AppUser user, boolean created) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result synchronize(IdentityProfile identity) {
        var existing = users.findForUpdateByAuthIssuerAndAuthSubject(identity.issuer(), identity.subject());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (existing.isPresent()) {
            AppUser user = existing.get();
            user.synchronizeIdentity(identity.displayName(), identity.email(), identity.emailVerified(), identity.issuedAt(), now);
            users.flush();
            return new Result(user, false);
        }
        AppUser user = new AppUser(identity.issuer(), identity.subject(), identity.displayName(),
                identity.email(), identity.emailVerified(), identity.issuedAt(), now);
        return new Result(users.saveAndFlush(user), true);
    }
}
