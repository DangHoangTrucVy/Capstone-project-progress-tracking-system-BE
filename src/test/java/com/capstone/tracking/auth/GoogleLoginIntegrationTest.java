package com.capstone.tracking.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.auth.google.GoogleIdentity;
import com.capstone.tracking.auth.google.GoogleSignInException;
import com.capstone.tracking.auth.google.GoogleTokenVerifier;
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Mục 9: Google sign-in succeeds only for accounts on an imported list; nobody is matched by name. */
class GoogleLoginIntegrationTest extends TestSupport {

    @MockBean private GoogleTokenVerifier googleTokenVerifier;

    @Test
    void googleConfigIsPublicAndOffWithoutClientIds() throws Exception {
        mockMvc.perform(get("/api/v1/auth/google/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.clientId").doesNotExist());
    }

    @Test
    void aVerifiedAccountOnNoListIsRefused() throws Exception {
        googleSays("stranger-" + suffix + "@gmail.com");
        login().andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("NOT_ON_LIST"));
        // A student account that is on no semester list is refused as well.
        User student = student("gg-unlisted");
        googleSays(student.getEmail());
        login().andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("NOT_ON_LIST"));
    }

    @Test
    void listedStudentAndImportedLecturerSignIn() throws Exception {
        User student = student("gg-student");
        Semester semester = semesterRepository.save(new Semester(freeSemesterCode(), "Google semester",
                LocalDate.now(), LocalDate.now().plusDays(90)));
        semesterStudentRepository.save(SemesterStudent.builder().semesterCode(semester.getCode()).user(student)
                .major(major()).currentTerm(8).build());
        googleSays(student.getEmail());
        login().andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("STUDENT"));

        User lecturer = user("gg-lecturer", Role.LECTURER);
        googleSays(lecturer.getEmail());
        login().andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("LECTURER"));
    }

    @Test
    void badTokenIsRejected() throws Exception {
        when(googleTokenVerifier.verify(anyString())).thenThrow(new GoogleSignInException("Invalid Google ID token"));
        login().andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("GOOGLE_SIGN_IN_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid Google ID token"));
    }

    @Test
    void aLecturerRoleWithoutAnImportedListEntryCannotSignIn() throws Exception {
        User lecturer = user("unimported-google", Role.LECTURER);
        lecturer.setLecturerImported(false);
        userRepository.save(lecturer);
        googleSays(lecturer.getEmail());
        login().andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("NOT_ON_LIST"));
    }

    private void googleSays(String email) {
        when(googleTokenVerifier.verify(anyString())).thenReturn(new GoogleIdentity(email, "Người dùng", null, null));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"token\"}"));
    }
}
