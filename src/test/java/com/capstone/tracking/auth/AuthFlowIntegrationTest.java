package com.capstone.tracking.auth;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowIntegrationTest extends WorkflowTestSupport {
    @Autowired private PasswordEncoder encoder;

    @Test
    void selfRegistrationIsDisabled() throws Exception {
        postJson("/api/v1/auth/register", user("visitor", Role.STUDENT),
                Map.of("email", "new@fpt.edu.vn", "fullName", "New", "password", "Password123"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("REGISTRATION_DISABLED"));
    }

    @Test
    void provisionedStaffCanUseExplicitPasswordFallbackButStudentsCannot() throws Exception {
        User instructor = user("login-staff", Role.INSTRUCTOR);
        instructor.setPasswordHash(encoder.encode("Password123"));
        userRepository.save(instructor);
        var response = body(postJson("/api/v1/auth/login", instructor,
                Map.of("email", instructor.getEmail(), "password", "Password123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty()));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + response.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("INSTRUCTOR"));
        getAs("/api/v1/users", instructor).andExpect(status().isForbidden());
        User student = user("login-student", Role.STUDENT);
        student.setPasswordHash(encoder.encode("Password123"));
        userRepository.save(student);
        // YC02: students sign in too; being eligible for the capstone is a separate check.
        var studentLogin = body(postJson("/api/v1/auth/login", student,
                Map.of("email", student.getEmail(), "password", "Password123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("STUDENT")));
        getAs("/api/v1/auth/me", student).andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(true));
        getAs("/api/v1/users", student).andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
