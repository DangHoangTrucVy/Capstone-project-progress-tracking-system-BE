package com.capstone.tracking.auth;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.auth.google.GoogleIdentity;
import com.capstone.tracking.auth.google.GoogleTokenVerifier;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Students without a school email sign up with a personal email + student code and wait for Admin approval. */
class RegistrationFlowIntegrationTest extends WorkflowTestSupport {

    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    private final String email = "sv-" + suffix + "@gmail.com";
    private final String studentCode = "SE" + ThreadLocalRandom.current().nextInt(100000, 1000000);

    @Test
    void signUpWaitsForApprovalThenSignsInWithPassword() throws Exception {
        String id = body(register(email, studentCode).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))).get("id").asText();

        login(email, "Password123").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_PENDING_APPROVAL"));
        login(email, "WrongPass1").andExpect(status().isUnauthorized());

        User admin = user("reg-admin", Role.ADMIN);
        getAs("/api/v1/registrations?size=100", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')].studentCode").value(studentCode));
        postJson("/api/v1/registrations/" + id + "/approve", admin, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        login(email, "Password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.studentCode").value(studentCode));
    }

    @Test
    void rejectedSignUpShowsTheReasonAndCanBeSentAgain() throws Exception {
        String id = body(register(email, studentCode).andExpect(status().isCreated())).get("id").asText();
        User admin = user("reg-admin", Role.ADMIN);
        postJson("/api/v1/registrations/" + id + "/reject", admin, Map.of("reason", "MSSV khong ton tai"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        login(email, "Password123").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_REJECTED"))
                .andExpect(jsonPath("$.message").value(containsString("MSSV khong ton tai")));

        register(email, studentCode).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void emailAndStudentCodeMustBeUnused() throws Exception {
        register(email, studentCode).andExpect(status().isCreated());
        register("other-" + suffix + "@gmail.com", studentCode).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STUDENT_CODE_TAKEN"));
        User provisioned = user("reg-existing", Role.STUDENT);
        register(provisioned.getEmail(), "SE" + ThreadLocalRandom.current().nextInt(100000, 1000000))
                .andExpect(status().isConflict());
        register("bad-" + suffix + "@gmail.com", "160368").andExpect(status().isBadRequest());
    }

    @Test
    void approvedStudentSignsInWithPersonalGoogleAccount() throws Exception {
        when(googleTokenVerifier.verify(anyString())).thenReturn(new GoogleIdentity(email, "SV", null, null));
        String id = body(register(email, studentCode).andExpect(status().isCreated())).get("id").asText();

        googleLogin().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_PENDING_APPROVAL"));
        postJson("/api/v1/registrations/" + id + "/approve", user("reg-admin", Role.ADMIN), Map.of())
                .andExpect(status().isOk());
        googleLogin().andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void unregisteredPersonalGoogleAccountMustSignUpFirst() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleIdentity("nobody-" + suffix + "@yahoo.com", "Nobody", null, null));
        googleLogin().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("REGISTRATION_REQUIRED"));
    }

    @Test
    void onlyAdminsReviewSignUps() throws Exception {
        getAs("/api/v1/registrations", user("reg-student", Role.STUDENT)).andExpect(status().isForbidden());
        getAs("/api/v1/registrations", user("reg-instructor", Role.INSTRUCTOR)).andExpect(status().isForbidden());
    }

    private ResultActions register(String email, String studentCode) throws Exception {
        return publicPost("/api/v1/auth/register", Map.of("email", email, "fullName", "Sinh Vien " + suffix,
                "password", "Password123", "studentCode", studentCode, "campus", "HO_CHI_MINH"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return publicPost("/api/v1/auth/login", Map.of("email", email, "password", password));
    }

    private ResultActions googleLogin() throws Exception {
        return publicPost("/api/v1/auth/google", Map.of("idToken", "token", "campus", "HO_CHI_MINH"));
    }

    private ResultActions publicPost(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
