package com.capstone.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.user.Role;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Revised v2.9 supplied 21/10: staff passwords, no lecturer roster, group finalization, post-final coordination. */
class RevisedRequirementsIntegrationTest extends TestSupport {
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired private com.capstone.tracking.notification.NotificationRepository notifications;
    @Autowired private com.capstone.tracking.audit.SystemAuditTrailRepository audits;

    @Test
    void staffPasswordCreationLoginAssignmentAndStudentPasswordRejection() throws Exception {
        var w = world(1, 0);
        String email = "new-moderator-" + suffix + "@gmail.com";
        var created = body(postJson("/api/v1/users/moderators", w.admin(), Map.of(
                "email", email, "fullName", "Created Moderator", "password", "Password123"))
                .andExpect(status().isCreated()));
        var moderator = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(encoder.matches("Password123", moderator.getPasswordHash())).isTrue();
        putJson("/api/v1/users/" + moderator.getId() + "/password", w.admin(), Map.of("password", "NewPassword123"))
                .andExpect(status().isNoContent());
        assertThat(created.toString()).doesNotContain("Password123", "passwordHash");
        postJson("/api/v1/auth/login", w.admin(), Map.of("email", email, "password", "NewPassword123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        putJson("/api/v1/semesters/" + w.code() + "/moderator", w.admin(), Map.of("moderatorId", moderator.getId()))
                .andExpect(status().isOk());
        getAs("/api/v1/semesters/" + w.code() + "/students", moderator).andExpect(status().isOk());
        postJson("/api/v1/auth/login", w.admin(), Map.of("email", w.students().get(0).getEmail(), "password", "Password123"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("GOOGLE_LOGIN_REQUIRED"));
        putJson("/api/v1/users/" + w.students().get(0).getId() + "/password", w.admin(), Map.of("password", "Password123"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/users", w.admin(), Map.of("email", "admin-" + suffix + "@gmail.com", "fullName", "No",
                "password", "Password123", "role", "ADMIN")).andExpect(status().isMethodNotAllowed());
        getAs("/api/v1/semesters/" + w.code() + "/students", w.moderator()).andExpect(status().isForbidden());
    }

    @Test
    void importAcceptsNoPhoneColumnChecksTermAndImportsEligibilityAndCredits() throws Exception {
        var admin = user("roster-admin", Role.ADMIN);
        String code = freeSemesterCode();
        postJson("/api/v1/semesters", admin, Map.of("code", code, "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(90).toString())).andExpect(status().isCreated());
        String csv = "MSSV,Họ tên,Email,Kỳ,Ngành,Điều kiện,Tỷ lệ tín chỉ,Lý do\n"
                + "C1" + suffix + ",Credit flag,credit-" + suffix + "@gmail.com,8,Credits " + suffix + ",ELIGIBLE,79,Nợ tín chỉ\n"
                + "C2" + suffix + ",Confirmed,confirmed-" + suffix + "@gmail.com,8,Credits " + suffix + ",ELIGIBLE,80,Đã xác nhận\n"
                + "C3" + suffix + ",Unknown,unknown-" + suffix + "@gmail.com,8,Credits " + suffix + ",,80,\n"
                + "C4" + suffix + ",Wrong term,wrong-" + suffix + "@gmail.com,9,Credits " + suffix + ",,,\n";
        var report = body(upload("/api/v1/semesters/" + code + "/imports/students", admin, "roster.csv", csv)
                .andExpect(status().isOk()));
        assertThat(report.get("newRows").asInt()).isEqualTo(3);
        assertThat(report.get("errorRows").asInt()).isEqualTo(1);
        var rows = body(getAs("/api/v1/semesters/" + code + "/students?size=10", admin)).get("content");
        assertThat(rows.toString()).contains("INELIGIBLE", "ELIGIBLE", "UNDECIDED", "80%").doesNotContain("C4" + suffix);
        assertThat(rows.get(0).has("finalized")).isFalse();
        getAs("/api/v1/semesters/" + code + "/students?size=101", admin).andExpect(status().isBadRequest());
        getAs("/api/v1/semesters/" + code, admin).andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void majorCannotBeChangedEvenInPreparationAndRetakesKeepSeparateMembership() throws Exception {
        var w = openingWorld(1, 0);
        var ss = semesterStudentRepository.findBySemesterCodeAndUserId(w.code(), w.students().get(0).getId()).orElseThrow();
        // The edit screen has no major: the request only changes contact details, never the major (3.3).
        putJson("/api/v1/semester-students/" + ss.getId(), w.admin(), Map.of("majorId", w.otherMajor().getId(),
                "phone", "0909090909")).andExpect(status().isOk()).andExpect(jsonPath("$.majorId").value(w.major().getId().toString()));
        assertThat(semesterStudentRepository.findById(ss.getId()).orElseThrow().getMajor().getId()).isEqualTo(w.major().getId());
    }

    @Test
    void approvedGroupsKeepTheirCodeTheEligibilityFileStillAppliesAndExportOnlyWarns() throws Exception {
        var w = world(4, 0);
        var s = w.students();
        var group = createGroup(w, s.get(0), "Finalized group");
        join(group, s.get(0), s.get(1));
        join(group, s.get(0), s.get(2));
        postJson("/api/v1/rooms/" + group + "/finalize-request", s.get(0), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + group + "/finalize-decision", w.moderator(), Map.of("approve", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalized").value(true))
                .andExpect(jsonPath("$.code").value(w.code() + "-" + w.major().getCode() + "-001"));
        String csv = "MSSV,Điều kiện,Lý do\n" + s.get(0).getStudentCode() + ",INELIGIBLE,Đổi ngành ở kỳ trước\n";
        upload("/api/v1/semesters/" + w.code() + "/imports/eligibility", w.admin(), "flags.csv", csv)
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedRows").value(1));
        assertThat(enrollmentRepository.findBySemesterAndUser(w.code(), s.get(0).getId()).orElseThrow().getEligibility())
                .isEqualTo(Eligibility.INELIGIBLE);
        getAs("/api/v1/rooms/" + group, w.moderator()).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.finalized").value(true)).andExpect(jsonPath("$.leaderId").value(s.get(1).getId().toString()));
        long before = notifications.countByRecipientIdAndReadAtIsNull(w.moderator().getId());
        getAs("/api/v1/semesters/" + w.code() + "/export-check", w.moderator()).andExpect(status().isOk())
                .andExpect(jsonPath("$.canExport").value(true)).andExpect(jsonPath("$.groupsUnderMinimum.length()").value(1));
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(w.moderator().getId())).isGreaterThan(before);
        getAs("/api/v1/semesters/" + w.code() + "/export", w.moderator()).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        // An approved group takes no new member before the supplement phase (7.1).
        postJson("/api/v1/rooms/" + group + "/moderation/add", w.moderator(), Map.of("userId", s.get(3).getId()))
                .andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + group + "/moderation/leader", w.moderator(), Map.of("userId", s.get(2).getId()))
                .andExpect(status().isOk());
        getAs("/api/v1/semesters/" + w.code() + "/rooms?finalized=true", w.moderator())
                .andExpect(jsonPath("$.totalElements").value(1));
        getAs("/api/v1/semesters/" + w.code() + "/statistics", w.moderator())
                .andExpect(jsonPath("$.total.groups").value(1));
    }

    @Test
    void obsoleteEndpointsAreRemovedAndAuditWritesAreDeferred() throws Exception {
        long before = audits.count();
        var w = world(1, 0);
        createGroup(w, w.students().get(0), "No audit");
        assertThat(audits.count()).isEqualTo(before);
        getAs("/api/v1/semesters/" + w.code() + "/audit", w.admin()).andExpect(status().isNotFound());
        getAs("/api/v1/imports/lecturers", w.admin()).andExpect(status().isNotFound());
        postJson("/api/v1/semesters/" + w.code() + "/archive", w.admin(), Map.of()).andExpect(status().isNotFound());
        getAs("/api/v1/semesters/" + w.code() + "/students", user("unassigned", Role.LECTURER))
                .andExpect(status().isForbidden());
        var outsider = user("unassigned-scope", Role.LECTURER);
        getAs("/api/v1/semesters/" + w.code() + "/phase-check", outsider).andExpect(status().isForbidden());
        getAs("/api/v1/scopes/" + w.scope().getId(), w.admin()).andExpect(status().isNotFound());
        getAs("/api/v1/semesters/" + w.code(), outsider).andExpect(status().isForbidden());
    }

    @Test
    void correctedEmailInvalidatesOldTokenEvenIfEmailIsReassigned() throws Exception {
        var w = world(2, 0);
        var first = w.students().get(0);
        var second = w.students().get(1);
        String oldToken = bearer(first);
        putJson("/api/v1/users/" + first.getId(), w.admin(), Map.of("fullName", first.getFullName(),
                "email", "corrected-" + suffix + "@gmail.com", "status", "ACTIVE")).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        putJson("/api/v1/users/" + second.getId(), w.admin(), Map.of("fullName", second.getFullName(),
                "email", first.getEmail(), "status", "ACTIVE")).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", oldToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void moderatorComplaintDecisionNotifiesBothStudentAndLeader() throws Exception {
        var w = world(2, 0);
        var leader = w.students().get(0);
        var member = w.students().get(1);
        var group = createGroup(w, leader, "Complaint");
        join(group, leader, member);
        postJson("/api/v1/rooms/" + group + "/lock", leader, Map.of()).andExpect(status().isOk());
        var request = body(postJson("/api/v1/rooms/" + group + "/leave", member, Map.of("reason", "Xin rời")))
                .get("request").get("id").asText();
        postJson("/api/v1/leave-requests/" + request + "/leader-decision", leader, Map.of("approve", false))
                .andExpect(status().isOk());
        postJson("/api/v1/leave-requests/" + request + "/complaint", member, Map.of("reason", "Khiếu nại"))
                .andExpect(status().isOk());
        long before = notifications.countByRecipientIdAndReadAtIsNull(leader.getId());
        postJson("/api/v1/leave-requests/" + request + "/moderator-decision", w.moderator(), Map.of("approve", false))
                .andExpect(status().isOk());
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(leader.getId())).isGreaterThan(before);
        getAs("/api/v1/rooms/" + group, leader).andExpect(jsonPath("$.size").value(2));
    }
}
