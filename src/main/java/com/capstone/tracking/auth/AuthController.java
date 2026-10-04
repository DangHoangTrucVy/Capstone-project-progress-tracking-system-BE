package com.capstone.tracking.auth;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.auth.dto.GoogleLoginRequest;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.auth.dto.LoginResponse;
import com.capstone.tracking.auth.dto.RegisterRequest;
import com.capstone.tracking.user.Campus;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, login, and current-session identity")
public class AuthController {

    private final AuthService authService;

    @Value("${app.google.client-ids:}")
    private String googleClientIds;

    /** clientId is what the FE passes to Google Identity Services; the button is hidden when it is null. */
    public record GoogleConfigResponse(boolean enabled, String clientId) {}

    @Operation(summary = "Register an account with email and password")
    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Log in with email and password, returns a JWT")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Giai đoạn 1: Google Workspace sign-in for the chosen campus; the returned user.role picks the dashboard. */
    @Operation(summary = "Log in with a Google Workspace ID token, returns a JWT")
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

    /** Options for the campus picker on the sign-in page. */
    @Operation(summary = "List campuses for the sign-in picker")
    @GetMapping("/campuses")
    public Campus[] campuses() {
        return Campus.values();
    }

    /** Reads the identity Spring Security already resolved from the Bearer token via JwtAuthenticationFilter. */
    @Operation(summary = "Current signed-in user")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal User currentUser) {
        return UserResponse.from(currentUser);
    }
}
