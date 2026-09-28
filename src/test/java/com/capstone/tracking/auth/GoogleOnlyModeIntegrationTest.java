package com.capstone.tracking.auth;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.Map;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "app.auth.password-login-enabled=false")
class GoogleOnlyModeIntegrationTest extends WorkflowTestSupport {
    @Test
    void passwordLoginCannotBypassGoogleOnlyMode() throws Exception {
        var admin = user("google-only-admin", Role.ADMIN);
        postJson("/api/v1/auth/login", admin, Map.of("email", admin.getEmail(), "password", "Password123"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("GOOGLE_LOGIN_REQUIRED"));
    }
}
