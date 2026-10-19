package com.capstone.tracking.auth;

import com.capstone.tracking.auth.dto.GoogleLoginRequest;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.auth.dto.LoginResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Sign-in (only accounts on the imported lists) and the current identity")
public class AuthController {

    private final AuthService authService;

    @Value("${app.google.client-ids:}")
    private String googleClientIds;

    /** clientId is what the FE passes to Google Identity Services; the button is hidden when it is null. */
    public record GoogleConfigResponse(boolean enabled, String clientId) {}

    @Operation(summary = "Log in with email and password (when enabled), returns a JWT")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Log in with a Google ID token, returns a JWT; the account must be on an imported list")
    @PostMapping("/google")
    public LoginResponse googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request);
    }

    @Operation(summary = "Google sign-in settings for the FE button (OAuth client id)")
    @GetMapping("/google/config")
    public GoogleConfigResponse googleConfig() {
        String clientId = Arrays.stream(googleClientIds.split(","))
                .map(String::trim).filter(StringUtils::hasText).findFirst().orElse(null);
        return new GoogleConfigResponse(clientId != null, clientId);
    }

    @Operation(summary = "Current signed-in user")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal User currentUser) {
        return UserResponse.from(currentUser);
    }
}
