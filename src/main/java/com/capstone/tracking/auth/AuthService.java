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
 * Signing in (mục 9). An account signs in only when it is on an imported list: a student must be on the student list
 * of some semester, a lecturer on the lecturer list; nobody is matched by name and nobody can claim another's student
 * code. A verified Google account that is on no list is refused. Ineligible students sign in and only view.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SemesterStudentRepository semesterStudents;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleTokenVerifier googleTokenVerifier;

    @Value("${app.jwt.access-token-exp-minutes}")
    private long accessTokenExpMinutes;

    public LoginResponse login(LoginRequest request) {
        throw new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_LOGIN_REQUIRED", "Sign in with your Google account");
    }

    @Transactional
    public LoginResponse googleLogin(GoogleLoginRequest request) {
        GoogleIdentity identity = googleTokenVerifier.verify(request.idToken());
        User user = userRepository.findByEmailIgnoreCase(identity.email()).orElseThrow(() ->
                new ApiException(HttpStatus.FORBIDDEN, "NOT_ON_LIST",
                        "This account is not on the imported student or lecturer list"));
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
        if (user.getRole() == Role.LECTURER && !user.isLecturerImported()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ON_LIST", "You are not on the imported lecturer list");
        }
        if (user.getRole() == Role.STUDENT && !semesterStudents.existsByUserId(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ON_LIST", "You are not on any semester's student list");
        }
    }

    private LoginResponse issueTokens(User user) {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        return LoginResponse.of(token, accessTokenExpMinutes * 60, UserResponse.from(user));
    }
}
