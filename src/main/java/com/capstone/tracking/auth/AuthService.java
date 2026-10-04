package com.capstone.tracking.auth;

import com.capstone.tracking.auth.dto.GoogleLoginRequest;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.auth.google.GoogleIdentity;
import com.capstone.tracking.auth.google.GoogleTokenVerifier;
import com.capstone.tracking.auth.dto.LoginResponse;
import com.capstone.tracking.auth.dto.RegisterRequest;
import com.capstone.tracking.auth.dto.RegisterResponse;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.capstone.tracking.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final PasswordEncoder passwordEncoder;

    @Value("${app.jwt.access-token-exp-minutes}")
    private long accessTokenExpMinutes;

    @Value("${app.security.allowed-email-domain}")
    private String allowedEmailDomains;

    @Value("${app.auth.password-login-enabled:false}")
    private boolean passwordLoginEnabled;

    /**
     * A student without a school email signs up with a personal email and student code; the account cannot sign in
     * until an Admin approves it. A rejected sign-up may be sent again with corrected details.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String studentCode = request.studentCode().trim().toUpperCase();

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && !(user.isSelfRegistered() && user.getStatus() == UserStatus.REJECTED)) {
            throw new ConflictException("An account with this email already exists");
        }
        User owner = userRepository.findByStudentCodeIgnoreCase(studentCode).orElse(null);
        if (owner != null && (user == null || !owner.getId().equals(user.getId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDENT_CODE_TAKEN",
                    "Student code " + studentCode + " is already registered");
        }

        if (user == null) {
            user = User.builder().email(email).role(Role.STUDENT).selfRegistered(true).build();
        }
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStudentCode(studentCode);
        user.setCampus(request.campus());
        user.setStatus(UserStatus.PENDING_APPROVAL);
        user.setRejectionReason(null);
        user = userRepository.save(user);

        return new RegisterResponse(user.getId(), user.getEmail(), user.getStatus(),
                "Sign-up received. An administrator will confirm you are a student of the school before you can sign in.");
    }

    public LoginResponse login(LoginRequest request) {
        String email = request.email().toLowerCase();
        User selfRegistered = userRepository.findByEmailIgnoreCase(email).filter(User::isSelfRegistered).orElse(null);
        // Students who signed up with a personal email have no school Google account, so they may always use a password.
        if (!passwordLoginEnabled && selfRegistered == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_LOGIN_REQUIRED", "Sign in with your school Google account");
        }
        loginAttemptLimiter.checkAllowed(email);
        if (selfRegistered != null && isAwaitingApprovalOrRejected(selfRegistered)) {
            // Only reveal the sign-up status to someone who knows the password.
            if (selfRegistered.getPasswordHash() == null
                    || !passwordEncoder.matches(request.password(), selfRegistered.getPasswordHash())) {
                loginAttemptLimiter.recordFailure(email);
                throw new BadCredentialsException("Bad credentials");
            }
            throw registrationStatusError(selfRegistered);
        }
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
     * A student who signed up with a personal Google account (Gmail) signs in with it once an Admin approved them.
     */
    @Transactional
    public LoginResponse googleLogin(GoogleLoginRequest request) {
        GoogleIdentity identity = googleTokenVerifier.verify(request.idToken());
        String email = identity.email();
        User user = userRepository.findByEmailIgnoreCase(email).filter(User::isSelfRegistered).orElse(null);
        if (user != null) {
            if (isAwaitingApprovalOrRejected(user)) {
                throw registrationStatusError(user);
            }
        } else {
            if (!isAllowedDomain(email)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_REQUIRED",
                        "Personal email accounts must sign up with a student code and be approved first");
            }
            if (identity.hostedDomain() == null || identity.hostedDomain().isBlank()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_REQUIRED", "Use a school Google Workspace account");
            }
            requireAllowedDomain("workspace@" + identity.hostedDomain().toLowerCase(), "sign in");

            user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() ->
                    new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_PROVISIONED",
                            "Ask an administrator to provision your account and role"));
        }

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

    private static boolean isAwaitingApprovalOrRejected(User user) {
        return user.getStatus() == UserStatus.PENDING_APPROVAL || user.getStatus() == UserStatus.REJECTED;
    }

    private static ApiException registrationStatusError(User user) {
        if (user.getStatus() == UserStatus.REJECTED) {
            String reason = user.getRejectionReason();
            return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_REJECTED",
                    reason == null || reason.isBlank() ? "Your sign-up was rejected" : "Your sign-up was rejected: " + reason);
        }
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING_APPROVAL",
                "Your sign-up is waiting for an administrator to confirm you are a student");
    }

    private boolean isAllowedDomain(String email) {
        return List.of(allowedEmailDomains.split(",")).stream().anyMatch(domain -> email.endsWith("@" + domain.trim()));
    }

    private void requireAllowedDomain(String email, String action) {
        List<String> domains = List.of(allowedEmailDomains.split(","));
        if (!isAllowedDomain(email)) {
            String allowedList = domains.stream().map(d -> "@" + d.trim()).reduce((a, b) -> a + ", " + b).orElse("");
            throw new BadRequestException("Only " + allowedList + " accounts may " + action);
        }
    }

    private LoginResponse issueTokens(User user) {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        return LoginResponse.of(token, accessTokenExpMinutes * 60, UserResponse.from(user));
    }
}
