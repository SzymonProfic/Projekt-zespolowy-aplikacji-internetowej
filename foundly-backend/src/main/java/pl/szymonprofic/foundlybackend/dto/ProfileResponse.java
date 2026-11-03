package pl.szymonprofic.foundlybackend.dto;

import pl.szymonprofic.foundlybackend.models.AppUser;
import java.time.Instant;
import java.util.UUID;

public record ProfileResponse(UUID id, String displayName, String email, boolean emailVerified,
                              Instant createdAt, Instant updatedAt) {
    public static ProfileResponse from(AppUser user) {
        return new ProfileResponse(user.getId(), user.getDisplayName(), user.getEmail(), user.isEmailVerified(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
