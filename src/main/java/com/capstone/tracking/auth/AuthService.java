package com.capstone.tracking.auth;

import com.capstone.tracking.auth.dto.GoogleLoginRequest;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.auth.dto.LoginResponse;
import com.capstone.tracking.auth.google.GoogleIdentity;
import com.capstone.tracking.auth.google.GoogleTokenVerifier;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Students use Google with an imported student email. Admin/Moderator use email and password.
 * Nobody is matched by name or allowed to claim another student's code. Ineligible students may still sign in to view.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SemesterStudentRepository semesterStudents;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final org.springframework.security.authentication.AuthenticationManager authenticationManager;
    private final LoginAttemptLimiter limiter;

    @Value("${app.jwt.access-token-exp-minutes}")
    private long accessTokenExpMinutes;

    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        limiter.checkAllowed(email);
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && user.getRole() == Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_LOGIN_REQUIRED", "Students must sign in with Google");
        }
        try {
            authenticationManager.authenticate(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    email, request.password()));
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            limiter.recordFailure(email);
            throw ex;
        }
        limiter.reset(email);
        if (user == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("Bad credentials");
        }
        return issueTokens(user);
    }

    @Transactional
    public LoginResponse googleLogin(GoogleLoginRequest request) {
        GoogleIdentity identity = googleTokenVerifier.verify(request.idToken());
        User user = userRepository.findByEmailIgnoreCase(identity.email()).orElseThrow(() ->
                new ApiException(HttpStatus.FORBIDDEN, "NOT_ON_LIST",
                        "This account is not on the imported student or lecturer list"));
        if (user.getRole() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_LOGIN_REQUIRED", "Admin and Moderator use email and password");
        }
        if (identity.email().toLowerCase(java.util.Locale.ROOT).endsWith("@fpt.edu.vn")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STUDENT_GOOGLE_EMAIL_REQUIRED", "Use the Google email in the training department list, not an FPT account");
        }
        if (!user.isAccountNonLocked()) {
            throw new LockedException("This account is suspended");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("This account is inactive");
        }
        requireOnList(user);
        if (user.getAvatarUrl() == null) {
            user.setAvatarUrl(identity.pictureUrl());
        }
        return issueTokens(user);
    }

    /** A student account exists because of an import, but it must be on some semester's list to sign in. */
    private void requireOnList(User user) {
        if (user.getRole() == Role.STUDENT && !semesterStudents.existsByUserId(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ON_LIST", "You are not on any semester's student list");
        }
    }

    private LoginResponse issueTokens(User user) {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        return LoginResponse.of(token, accessTokenExpMinutes * 60, UserResponse.from(user));
    }
}
