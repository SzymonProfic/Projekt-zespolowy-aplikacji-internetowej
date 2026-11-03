package pl.szymonprofic.foundlybackend.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import pl.szymonprofic.foundlybackend.models.AppUser;
import java.util.*;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByAuthIssuerAndAuthSubject(String issuer, String subject);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUser> findForUpdateByAuthIssuerAndAuthSubject(String issuer, String subject);
}
