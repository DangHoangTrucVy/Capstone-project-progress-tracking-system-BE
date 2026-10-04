package com.capstone.tracking.auth;

import com.capstone.tracking.auth.google.GoogleIdentity;
import com.capstone.tracking.auth.google.GoogleSignInException;
import com.capstone.tracking.auth.google.GoogleTokenVerifier;
import com.capstone.tracking.user.Campus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Giai đoạn 1: campus + Google Workspace sign-in; the role comes from the account provisioned for the email. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleLoginIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void googleConfigIsPublicAndOffWithoutClientIds() throws Exception {
        mockMvc.perform(get("/api/v1/auth/google/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.clientId").doesNotExist());
    }

    @Test
    void unknownSchoolEmailCannotSelfRegister() throws Exception {
        String email = "gg-new-" + suffix + "@fpt.edu.vn";
        googleSays(email);
        login(Campus.HA_NOI).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_PROVISIONED"));
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    void studentSignsInEvenWhenNotEligible() throws Exception {
        String email = "gg-student-" + suffix + "@fpt.edu.vn";
        userRepository.save(User.builder().email(email).fullName("Member").role(Role.STUDENT)
                .eligible(false).ineligibleReason("Chua du tin chi").status(UserStatus.ACTIVE).build());
        googleSays(email);
        login(Campus.HA_NOI).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.eligible").value(false))
                .andExpect(jsonPath("$.user.ineligibleReason").value("Chua du tin chi"));
    }

    @Test
    void personalGoogleAccountIsRejected() throws Exception {
        when(googleTokenVerifier.verify(anyString())).thenReturn(new GoogleIdentity("person@fpt.edu.vn", "Person", null, null));
        login(Campus.HA_NOI).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("WORKSPACE_REQUIRED"));
    }

    @Test
    void provisionedAccountKeepsItsRole() throws Exception {
        String email = "gg-council-" + suffix + "@fpt.edu.vn";
        userRepository.save(User.builder().email(email).fullName("Council").role(Role.COUNCIL)
                .status(UserStatus.ACTIVE).build());
        googleSays(email);

        login(Campus.DA_NANG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("COUNCIL"))
                .andExpect(jsonPath("$.user.campus").value("DA_NANG"));
        login(Campus.HA_NOI).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CAMPUS_MISMATCH"));
    }

    @Test
    void outsideDomainAndBadTokenAreRejected() throws Exception {
        googleSays("someone-" + suffix + "@outlook.com");
        // A personal email has to sign up (student code + Admin approval) before it can sign in.
        login(Campus.HA_NOI).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("REGISTRATION_REQUIRED"));

        when(googleTokenVerifier.verify(anyString())).thenThrow(new GoogleSignInException("Invalid Google ID token"));
        login(Campus.HA_NOI).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("GOOGLE_SIGN_IN_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid Google ID token"));
    }

    @Test
    void campusesArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/campuses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Campus.values().length));
    }

    private void googleSays(String email) {
        when(googleTokenVerifier.verify(anyString())).thenReturn(new GoogleIdentity(email, "Người dùng", null, "fpt.edu.vn"));
    }

    private ResultActions login(Campus campus) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"token\",\"campus\":\"" + campus + "\"}"));
    }
}
