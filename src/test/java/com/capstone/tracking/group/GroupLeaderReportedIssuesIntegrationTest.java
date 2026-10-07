package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression tests for the group-formation issues reported by the FE team. */
class GroupLeaderReportedIssuesIntegrationTest extends WorkflowTestSupport {

    private UUID createGroup(User leader) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", "S-" + suffix))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private User studentWithCode(String name, String code) {
        return userRepository.save(User.builder().email(name + "-" + suffix + "@gmail.com").fullName(name + " " + suffix)
                .studentCode(code).passwordHash("x").role(Role.STUDENT).status(UserStatus.ACTIVE).eligible(true).build());
    }

    private UUID invite(User leader, UUID group, User student) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups/" + group + "/invites", leader,
                Map.of("userId", student.getId())).andExpect(status().isCreated())).get("id").asText());
    }

    private void accept(User student, UUID invite) throws Exception {
        postJson("/api/v1/invites/" + invite + "/accept", student, Map.of()).andExpect(status().isCreated());
    }

    private JsonNode notification(User as, String type) throws Exception {
        String json = getAs("/api/v1/notifications", as).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode n : objectMapper.readTree(json).get("content")) {
            if (type.equals(n.get("type").asText())) {
                return n;
            }
        }
        return null;
    }

    private void flag(User admin, User student, String reason) throws Exception {
        mockMvc.perform(put("/api/v1/eligibility/" + student.getId()).header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("eligible", false, "reason", reason))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#1 Leader invites a student by MSSV (studentCode / mssv / studentId / identifier)")
    void leaderInvitesByStudentCode() throws Exception {
        User leader = user("i-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        String url = "/api/v1/groups/" + group + "/invites";

        String[] fields = {"studentCode", "mssv", "studentId", "identifier"};
        for (int i = 0; i < fields.length; i++) {
            String code = "SE" + i + suffix.toUpperCase();
            User s = studentWithCode("i-s" + i, code);
            // Lower case on purpose: MSSV lookups ignore case.
            JsonNode created = body(postJson(url, leader, Map.of(fields[i], code.toLowerCase(), "message", "Join us"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("INVITE"))
                    .andExpect(jsonPath("$.status").value("PENDING")));
            assertThat(created.get("student").get("userId").asText()).isEqualTo(s.getId().toString());
            assertThat(notification(s, "JOIN_INVITE_RECEIVED")).isNotNull();
        }
        // The invited student accepts and becomes a member.
        String lastInvite = body(getAs("/api/v1/me/invites", userRepository.findByStudentCodeIgnoreCase("SE3" + suffix).orElseThrow())).get(0).get("id").asText();
        accept(userRepository.findByStudentCodeIgnoreCase("SE3" + suffix).orElseThrow(), UUID.fromString(lastInvite));

        postJson(url, leader, Map.of("studentCode", "SE-NOPE-" + suffix)).andExpect(status().isNotFound());
        postJson(url, leader, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#2 Reject reason is stored and forwarded to the applicant")
    void rejectReasonReachesTheStudent() throws Exception {
        User leader = user("r-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User applicant = user("r-app", Role.STUDENT);
        String app = body(postJson("/api/v1/groups/" + group + "/applications", applicant, Map.of())
                .andExpect(status().isCreated())).get("id").asText();

        postJson("/api/v1/applications/" + app + "/reject", leader, Map.of("reason", "Nhóm đã đủ người mảng BE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Nhóm đã đủ người mảng BE"));

        getAs("/api/v1/me/applications", applicant)
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].rejectReason").value("Nhóm đã đủ người mảng BE"));
        JsonNode n = notification(applicant, "JOIN_APPLICATION_REJECTED");
        assertThat(n).isNotNull();
        assertThat(n.get("message").asText()).contains("Lý do: Nhóm đã đủ người mảng BE");
        assertThat(n.get("details").asText()).isEqualTo("Nhóm đã đủ người mảng BE");

        // Alias field names and an empty body still work.
        User second = user("r-app2", Role.STUDENT);
        String app2 = body(postJson("/api/v1/groups/" + group + "/applications", second, Map.of())).get("id").asText();
        postJson("/api/v1/applications/" + app2 + "/reject", leader, Map.of("rejectReason", "Thiếu kỹ năng"))
                .andExpect(jsonPath("$.rejectReason").value("Thiếu kỹ năng"));
        User third = user("r-app3", Role.STUDENT);
        String app3 = body(postJson("/api/v1/groups/" + group + "/applications", third, Map.of())).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/applications/" + app3 + "/reject").header("Authorization", bearer(leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejectReason").doesNotExist());
        assertThat(notification(third, "JOIN_APPLICATION_REJECTED").get("message").asText()).doesNotContain("Lý do");
    }

    @Test
    @DisplayName("#3/#4 Leader locks and unlocks their own group; not someone else's, nor an Admin's lock")
    void leaderLocksAndUnlocks() throws Exception {
        User admin = user("l-admin", Role.ADMIN);
        User leader = user("l-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User member = user("l-member", Role.STUDENT);
        accept(member, invite(leader, group, member));
        User otherLeader = user("l-other", Role.STUDENT);
        createGroup(otherLeader);

        postJson("/api/v1/groups/" + group + "/lock", member, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/lock", otherLeader, Map.of()).andExpect(status().isForbidden());

        postJson("/api/v1/groups/" + group + "/lock", leader, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockedByAdmin").value(false));
        // Locked roster: no invites, no kicks by the leader.
        postJson("/api/v1/groups/" + group + "/invites", leader, Map.of("userId", user("l-x", Role.STUDENT).getId()))
                .andExpect(status().isConflict());
        UUID memberRow = groupMemberRepository.findByGroupIdAndUserId(group, member.getId()).orElseThrow().getId();
        mockMvc.perform(delete("/api/v1/groups/" + group + "/members/" + memberRow).header("Authorization", bearer(leader)))
                .andExpect(status().isConflict());

        postJson("/api/v1/groups/" + group + "/unlock", otherLeader, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/unlock", leader, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false));
        invite(leader, group, user("l-y", Role.STUDENT));

        // An Admin's lock: the leader cannot lift it, the Admin can.
        postJson("/api/v1/groups/" + group + "/lock", admin, Map.of()).andExpect(jsonPath("$.lockedByAdmin").value(true));
        postJson("/api/v1/groups/" + group + "/unlock", leader, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/unlock", admin, Map.of()).andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    @DisplayName("#5 Roster submit works without a supervisor: Admins are notified, then review it")
    void rosterSubmitWithoutSupervisor() throws Exception {
        User admin = user("s-admin", Role.ADMIN);
        User leader = user("s-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        for (int i = 0; i < 2; i++) {
            User m = user("s-m" + i, Role.STUDENT);
            accept(m, invite(leader, group, m));
        }
        // Finalize first, then submit: submitting is allowed on a locked roster.
        postJson("/api/v1/groups/" + group + "/lock", leader, Map.of()).andExpect(status().isOk());
        postJson("/api/v1/groups/" + group + "/roster/submit", leader, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.rosterStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.supervisorId").doesNotExist());
        assertThat(notification(admin, "ROSTER_SUBMITTED")).isNotNull();

        postJson("/api/v1/groups/" + group + "/roster/review", admin, Map.of("approved", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rosterStatus").value("APPROVED"));
        assertThat(notification(leader, "ROSTER_REVIEWED")).isNotNull();
    }

    @Test
    @DisplayName("#6 A student marked ineligible leaves their group automatically")
    void ineligibleStudentLeavesGroup() throws Exception {
        User admin = user("e-admin", Role.ADMIN);
        User leader = user("e-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User member = user("e-member", Role.STUDENT);
        User second = user("e-second", Role.STUDENT);
        accept(member, invite(leader, group, member));
        accept(second, invite(leader, group, second));
        // Even an Admin-locked roster.
        postJson("/api/v1/groups/" + group + "/lock", admin, Map.of()).andExpect(status().isOk());

        flag(admin, member, "Nợ môn");
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, member.getId(), MemberStatus.ACTIVE)).isFalse();
        getAs("/api/v1/groups/" + group, leader).andExpect(jsonPath("$.memberCount").value(2));
        getAs("/api/v1/groups/" + group, member).andExpect(status().isForbidden());
        assertThat(notification(member, "MEMBER_REMOVED").get("message").asText()).contains("Nợ môn");
        assertThat(notification(leader, "MEMBER_REMOVED")).isNotNull();

        // The leader is flagged: they leave and the earliest-joined remaining member takes over.
        flag(admin, leader, "Đình chỉ");
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, leader.getId(), MemberStatus.ACTIVE)).isFalse();
        assertThat(userRepository.findById(leader.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        GroupMember newLeader = groupMemberRepository.findByGroupIdAndUserIdAndStatus(group, second.getId(), MemberStatus.ACTIVE)
                .orElseThrow();
        assertThat(newLeader.isLeader()).isTrue();
        assertThat(userRepository.findById(second.getId()).orElseThrow().getRole()).isEqualTo(Role.GROUP_LEADER);

        // Lifting the flag does not put them back, but they may join again.
        mockMvc.perform(put("/api/v1/eligibility/" + member.getId()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"eligible\":true}")).andExpect(status().isOk());
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, member.getId(), MemberStatus.ACTIVE)).isFalse();
        createGroup(member);

        // The CSV/JSON import path also removes flagged students.
        User imported = user("e-imported", Role.STUDENT);
        UUID g2 = createGroup(imported);
        postJson("/api/v1/eligibility/import", admin, Map.of("students", java.util.List.of(
                Map.of("email", imported.getEmail(), "eligible", false, "reason", "Chưa đủ tín chỉ"))))
                .andExpect(status().isOk());
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(g2, imported.getId(), MemberStatus.ACTIVE)).isFalse();
    }
}
