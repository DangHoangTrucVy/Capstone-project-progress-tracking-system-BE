package com.capstone.tracking.auth;

import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Brute-force guard on /auth/login (in-memory limiter here; the Redis one is covered by InfrastructureIntegrationTest). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoginRateLimitIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void fiveWrongPasswordsLockTheEmailEvenForTheRightPassword() throws Exception {
        String email = "rl-" + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn";
        userRepository.save(User.builder().email(email).fullName("Rate Limit").passwordHash(passwordEncoder.encode("Right@123"))
                .role(Role.STUDENT).status(UserStatus.ACTIVE).build());

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            login(email, "wrong").andExpect(status().isUnauthorized());
        }
        login(email, "Right@123")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    @Test
    void successfulLoginResetsTheCounter() throws Exception {
        String email = "rl-" + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn";
        userRepository.save(User.builder().email(email).fullName("Rate Limit").passwordHash(passwordEncoder.encode("Right@123"))
                .role(Role.STUDENT).status(UserStatus.ACTIVE).build());

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES - 1; i++) {
            login(email, "wrong").andExpect(status().isUnauthorized());
        }
        login(email, "Right@123").andExpect(status().isOk());
        login(email, "wrong").andExpect(status().isUnauthorized());
        login(email, "Right@123").andExpect(status().isOk());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
