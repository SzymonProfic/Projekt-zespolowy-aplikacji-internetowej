package pl.szymonprofic.foundlybackend.services;

import jakarta.validation.constraints.*;

import java.time.Instant;

public record IdentityProfile(@NotBlank @Size(max = 512) String issuer,
                              @NotBlank @Size(max = 255) String subject,
                              @NotBlank @Size(max = 100) String displayName,
                              @Email @Size(max = 254) String email,
                              boolean emailVerified, @NotNull Instant issuedAt) {
}
