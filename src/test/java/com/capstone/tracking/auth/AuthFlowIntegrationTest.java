package com.capstone.tracking.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Mục 9: only accounts on an imported list sign in. */
class AuthFlowIntegrationTest extends TestSupport {

    @Autowired private PasswordEncoder encoder;

    @Test
    void lecturerCannotUsePasswordEvenWhenTheLegacyFlagIsEnabled() throws Exception {
        User lecturer = withPassword(user("login-lecturer", Role.LECTURER));
        login(lecturer).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("GOOGLE_LOGIN_REQUIRED"));
        getAs("/api/v1/users", lecturer).andExpect(status().isForbidden());
    }

    @Test
    void aStudentSignsInOnlyWhenOnASemesterList() throws Exception {
        User student = withPassword(student("login-student"));
        login(student).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("GOOGLE_LOGIN_REQUIRED"));

        Semester semester = semesterRepository.save(new Semester(freeSemesterCode(), "Login semester",
                LocalDate.now(), LocalDate.now().plusDays(90)));
        semesterStudentRepository.save(SemesterStudent.builder().semesterCode(semester.getCode()).user(student)
                .major(major()).currentTerm(8).build());
        login(student).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("GOOGLE_LOGIN_REQUIRED"));
        getAs("/api/v1/me/semesters", student).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].semesterCode").value(semester.getCode()))
                .andExpect(jsonPath("$[0].readOnly").value(false));
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    private User withPassword(User user) {
        user.setPasswordHash(encoder.encode("Password123"));
        return userRepository.save(user);
    }

    private org.springframework.test.web.servlet.ResultActions login(User user) throws Exception {
        return postJson("/api/v1/auth/login", user, Map.of("email", user.getEmail(), "password", "Password123"));
    }
}
