package com.capstone.tracking.auth;

import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.auth.dto.LoginResponse;
import com.capstone.tracking.auth.dto.RegisterRequest;
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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptLimiter loginAttemptLimiter;

    @Value("${app.jwt.access-token-exp-minutes}")
    private long accessTokenExpMinutes;

    @Value("${app.security.allowed-email-domain}")
    private String allowedEmailDomains;

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase();

        List<String> domains = List.of(allowedEmailDomains.split(","));
        boolean domainAllowed = domains.stream().anyMatch(domain -> email.endsWith("@" + domain.trim()));
        if (!domainAllowed) {
            String allowedList = domains.stream().map(d -> "@" + d.trim()).reduce((a, b) -> a + ", " + b).orElse("");
            throw new BadRequestException("Only " + allowedList + " accounts may self-register");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A user with email " + email + " already exists");
        }

        User user = User.builder()
                .email(email)
                .fullName(request.fullName())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(user);

        return issueTokens(user);
    }

    public LoginResponse login(LoginRequest request) {
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

    private LoginResponse issueTokens(User user) {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        return LoginResponse.of(token, accessTokenExpMinutes * 60, UserResponse.from(user));
    }
}
