package com.capstone.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.capstone.tracking.user.Role;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression coverage for the confirmed changes of 20 October and the gaps found in the v2.9 audit. */
class RequirementsV29IntegrationTest extends TestSupport {

    @Test
    void aNewSemesterHasDefaultRulesAndImportCreatesOneScopePerMajor() throws Exception {
        var admin = user("auto-cap-admin", Role.ADMIN);
        String semester = freeSemesterCode();
        postJson("/api/v1/semesters", admin, Map.of("code", semester, "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(100).toString())).andExpect(status().isCreated());
        var rules = body(getAs("/api/v1/semesters/" + semester + "/rules", admin).andExpect(status().isOk()));
        assertThat(rules.get("maxGroupSize").asInt()).isEqualTo(5);
        assertThat(rules.get("defaultMinRoomSize").asInt()).isEqualTo(3);
        assertThat(rules.get("maxPendingRequests").asInt()).isEqualTo(3);
        assertThat(rules.get("requestTtlMinutes").asInt()).isEqualTo(15);
        assertThat(rules.get("minDeadlineNoticeMinutes").asInt()).isEqualTo(10);
        String csv = "MSSV,Họ tên,Email,Số điện thoại,Kỳ,Ngành\nAUTO" + suffix
                + ",Imported,auto-" + suffix + "@gmail.com,0900000000,8,Auto " + suffix + "\n";
        upload("/api/v1/semesters/" + semester + "/imports/students", admin, "auto.csv", csv)
                .andExpect(status().isOk());
        assertThat(scopeRepository.findBySemester(semester)).hasSize(1);
        assertThat(enrollmentRepository.findBySemester(semester)).hasSize(1);
        var moderator = user("auto-cap-moderator", Role.LECTURER);
        putJson("/api/v1/semesters/" + semester + "/moderator", admin, Map.of("lecturerId", moderator.getId()))
                .andExpect(status().isOk());
        getAs("/api/v1/semesters/" + semester + "/majors", moderator).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students").value(1));
    }

    @Test
    void moderatorCanArrangeLockedGroupsBeforeDeadlineAndAppointLeaderWithoutConsent() throws Exception {
        var w = world(5, 1);
        var s = w.students();
        var target = createGroup(w, s.get(0), "Target");
        join(target, s.get(0), s.get(1));
        var source = createGroup(w, s.get(2), "Source");
        postJson("/api/v1/rooms/" + target + "/lock", s.get(0), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + source + "/lock", s.get(2), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + target + "/moderation/add", w.moderator(), Map.of("userId", s.get(3).getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(3));
        postJson("/api/v1/rooms/" + target + "/moderation/leader", w.moderator(), Map.of("userId", s.get(1).getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.leaderId").value(s.get(1).getId().toString()));
        postJson("/api/v1/rooms/" + target + "/moderation/merge", w.moderator(), Map.of("sourceRoomId", source))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(4));
        // The source room went back to empty; it is kept.
        assertThat(groupRepository.findById(source).orElseThrow().isDissolved()).isFalse();
        assertThat(memberRepository.countActive(source)).isZero();
        postJson("/api/v1/rooms/" + target + "/moderation/add", w.moderator(), Map.of("userId", w.others().get(0).getId()))
                .andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + target + "/moderation/leader", s.get(0), Map.of("userId", s.get(0).getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void mergeCannotExceedFiveAndRollsBackWithoutMovingAnyone() throws Exception {
        var w = world(6, 0);
        var s = w.students();
        var a = createGroup(w, s.get(0), "A");
        var b = createGroup(w, s.get(3), "B");
        join(a, s.get(0), s.get(1));
        join(a, s.get(0), s.get(2));
        join(b, s.get(3), s.get(4));
        join(b, s.get(3), s.get(5));
        postJson("/api/v1/rooms/" + a + "/moderation/merge", w.moderator(), Map.of("sourceRoomId", b))
                .andExpect(status().isConflict());
        assertThat(memberRepository.countActive(a)).isEqualTo(3);
        assertThat(memberRepository.countActive(b)).isEqualTo(3);
    }

    @Test
    void afterRound1TheModeratorPlacesLeftoversIntoApprovedGroupsWhileStudentsOnlyTransferTheLeaderRole() throws Exception {
        var w = world(4, 0);
        var s = w.students();
        var group = createGroup(w, s.get(0), "Finalized");
        join(group, s.get(0), s.get(1));
        join(group, s.get(0), s.get(2));
        postJson("/api/v1/rooms/" + group + "/finalize-decision", w.moderator(), Map.of("approve", true))
                .andExpect(status().isOk());
        passDeadline(w);
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("SUPPLEMENT"));
        // Students cannot walk in any more.
        postJson("/api/v1/rooms/" + w.rooms().get(1) + "/join", s.get(3), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + group + "/moderation/add", w.moderator(), Map.of("userId", s.get(3).getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalized").value(true)).andExpect(jsonPath("$.size").value(4));
        var ss = semesterStudentRepository.findBySemesterCodeAndUserId(w.code(), s.get(0).getId()).orElseThrow();
        putJson("/api/v1/semester-students/" + ss.getId(), w.moderator(), Map.of("fullName", "Changed"))
                .andExpect(status().isOk());
        var transfer = id(postJson("/api/v1/rooms/" + group + "/leader-transfers", s.get(0),
                Map.of("toUserId", s.get(1).getId(), "leaveAfter", false)).andExpect(status().isCreated()));
        postJson("/api/v1/leader-transfers/" + transfer + "/accept", s.get(1), Map.of()).andExpect(status().isOk());
        assertThat(groupRepository.findById(group).orElseThrow().isFinalized()).isTrue();
        // Nobody leaves an approved group by themselves: the request goes to the Moderator.
        postJson("/api/v1/rooms/" + group + "/leave", s.get(2), Map.of("reason", "x")).andExpect(status().isOk())
                .andExpect(jsonPath("$.left").value(false)).andExpect(jsonPath("$.request.status").value("PENDING_MODERATOR"));
    }

    @Test
    void duplicateEmailsAndInvalidTermsAreReportedBeforeCommitAndNewMajorsCannotBypassOpenedRoster() throws Exception {
        var w = world(1, 0);
        String csv = "MSSV,Họ tên,Email,Số điện thoại,Kỳ,Ngành\n"
                + "D1" + suffix + ",One,duplicate" + suffix + "@gmail.com,,8," + w.major().getCode() + "\n"
                + "D2" + suffix + ",Two,duplicate" + suffix + "@gmail.com,,8," + w.major().getCode() + "\n"
                + "D3" + suffix + ",Bad Term,bad" + suffix + "@gmail.com,,8.5," + w.major().getCode() + "\n"
                + "D4" + suffix + ",New Major,new" + suffix + "@gmail.com,,8,Entirely new major " + suffix + "\n";
        var report = body(upload("/api/v1/semesters/" + w.code() + "/imports/students/preview", w.admin(), "bad.csv", csv)
                .andExpect(status().isOk()));
        assertThat(report.get("errorRows").asInt()).isEqualTo(4);
        assertThat(report.get("errors").toString()).contains("Trùng email", "Sai định dạng kỳ học", "không thêm sinh viên mới");
        assertThat(semesterStudentRepository.countBySemesterCode(w.code())).isEqualTo(1);
    }

    @Test
    void adminCanAssignModeratorWithoutAnyLecturerImport() throws Exception {
        var w = world(1, 0);
        var lecturer = user("created-moderator", Role.LECTURER);
        putJson("/api/v1/semesters/" + w.code() + "/moderator", w.admin(), Map.of("moderatorId", lecturer.getId()))
                .andExpect(status().isOk());
        postJson("/api/v1/users", w.admin(), Map.of("email", "admin2" + suffix + "@gmail.com", "fullName", "Forbidden",
                "password", "Password123", "role", "ADMIN")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aPrivateInvitationDoesNotRevealTheInvitedStudentsProfileToTheGroup() throws Exception {
        var w = world(2, 0);
        var leader = w.students().get(0);
        var hidden = w.students().get(1);
        hidden.setProfilePrivate(true);
        hidden.setSkills("SecretSkill");
        userRepository.save(hidden);
        var group = createGroup(w, leader, "Privacy");
        var invitation = id(postJson("/api/v1/rooms/" + group + "/invitations", leader,
                Map.of("studentId", hidden.getId())).andExpect(status().isCreated()));
        assertThat(body(getAs("/api/v1/requests/" + invitation, leader)).toString()).doesNotContain("SecretSkill");
    }
}
