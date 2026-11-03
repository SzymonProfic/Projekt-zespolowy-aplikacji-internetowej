package pl.szymonprofic.foundlybackend.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pl.szymonprofic.foundlybackend.dto.ProfileResponse;
import pl.szymonprofic.foundlybackend.services.CurrentUserService;
import java.net.URI;

@RestController
@RequiredArgsConstructor
public class ProfileController {
    private final CurrentUserService users;

    @GetMapping("/api/me")
    @Operation(summary = "Odczytaj własny profil", description = "Nie tworzy ani nie synchronizuje profilu; brak profilu daje 404.")
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return ProfileResponse.from(users.get(jwt));
    }

    @PutMapping("/api/me")
    @Operation(summary = "Utwórz lub zsynchronizuj własny profil",
            description = "Wywołaj po zalogowaniu i odświeżeniu tokenu. Bez body; dane pochodzą wyłącznie z access tokenu Keycloak.")
    @ApiResponse(responseCode = "200", description = "Istniejący profil zsynchronizowany")
    @ApiResponse(responseCode = "201", description = "Profil utworzony")
    public ResponseEntity<ProfileResponse> synchronize(@AuthenticationPrincipal Jwt jwt) {
        var result = users.synchronize(jwt);
        ProfileResponse profile = ProfileResponse.from(result.user());
        return result.created() ? ResponseEntity.created(URI.create("/api/me")).body(profile)
                : ResponseEntity.ok(profile);
    }
}
