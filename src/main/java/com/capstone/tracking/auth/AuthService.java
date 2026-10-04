package com.capstone.tracking.auth;

import com.capstone.tracking.auth.dto.GoogleLoginRequest;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.auth.google.GoogleIdentity;
import com.capstone.tracking.auth.google.GoogleTokenVerifier;
import com.capstone.tracking.auth.dto.LoginResponse;
import com.capstone.tracking.auth.dto.RegisterRequest;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final GoogleTokenVerifier googleTokenVerifier;

    @Value("${app.jwt.access-token-exp-minutes}")
    private long accessTokenExpMinutes;

    @Value("${app.security.allowed-email-domain}")
    private String allowedEmailDomains;

    @Value("${app.auth.password-login-enabled:false}")
    private boolean passwordLoginEnabled;

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED",
                "Accounts and group leaders must be provisioned by an administrator");
    }

    public LoginResponse login(LoginRequest request) {
        if (!passwordLoginEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_LOGIN_REQUIRED", "Sign in with your school Google account");
        }
        String email = request.email().toLowerCase();
        loginAttemptLimiter.checkAllowed(email);
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException e) {
            loginAttemptLimiter.recordFailure(email);
            throw e;
        }
        loginAttemptLimiter.reset(email);

        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new BadRequestException("Email or password is incorrect"));

        return issueTokens(user);
    }

    /**
     * Giai đoạn 1: sign in with the school's Google Workspace account. The role comes from the account an Admin
     * provisioned for that email; unknown accounts and students sign in too, whether or not they are eligible. The
     * campus picked on the first sign-in is pinned to the account, and signing in under another campus is refused.
     */
    @Transactional
    public LoginResponse googleLogin(GoogleLoginRequest request) {
        GoogleIdentity identity = googleTokenVerifier.verify(request.idToken());
        String email = identity.email();
        requireAllowedDomain(email, "sign in");
        if (identity.hostedDomain() == null || identity.hostedDomain().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_REQUIRED", "Use a school Google Workspace account");
        }
        requireAllowedDomain("workspace@" + identity.hostedDomain().toLowerCase(), "sign in");

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() ->
                new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_PROVISIONED",
                        "Ask an administrator to provision your account and role"));

        if (!user.isAccountNonLocked()) {
            throw new LockedException("This account is suspended");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("This account is inactive");
        }
        if (user.getCampus() == null) {
            user.setCampus(request.campus());
        } else if (user.getCampus() != request.campus()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CAMPUS_MISMATCH",
                    "This account belongs to campus " + user.getCampus());
        }
        if (user.getAvatarUrl() == null) {
            user.setAvatarUrl(identity.pictureUrl());
        }
        return issueTokens(user);
    }

    private void requireAllowedDomain(String email, String action) {
        List<String> domains = List.of(allowedEmailDomains.split(","));
        boolean domainAllowed = domains.stream().anyMatch(domain -> email.endsWith("@" + domain.trim()));
        if (!domainAllowed) {
            String allowedList = domains.stream().map(d -> "@" + d.trim()).reduce((a, b) -> a + ", " + b).orElse("");
            throw new BadRequestException("Only " + allowedList + " accounts may " + action);
        }
    }

    private LoginResponse issueTokens(User user) {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        return LoginResponse.of(token, accessTokenExpMinutes * 60, UserResponse.from(user));
    }
}
