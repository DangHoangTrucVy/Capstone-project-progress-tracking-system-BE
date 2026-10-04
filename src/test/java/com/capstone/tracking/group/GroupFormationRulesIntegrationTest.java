package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rules added on top of the formation flow: one official group per capstone round (semester) (YC07, YC13, YC21), the
 * Admin's per-semester formation deadline (YC17, YC21), eligibility notices and CSV import (YC03) and the end of the
 * recruiting-profile right once an Apply's Invite dies (YC22).
 */
class GroupFormationRulesIntegrationTest extends WorkflowTestSupport {

    @Autowired private GroupJoinRequestRepository joinRequests;

    private final String semA = "A-" + suffix;
    private final String semB = "B-" + suffix;

    private UUID createGroup(User leader, String semester) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", semester))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID apply(User student, UUID groupId) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups/" + groupId + "/applications", student, Map.of())
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID invite(User leader, UUID groupId, User student) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups/" + groupId + "/invites", leader,
                Map.of("userId", student.getId())).andExpect(status().isCreated())).get("id").asText());
    }

    private void accept(User student, UUID inviteId) throws Exception {
        postJson("/api/v1/invites/" + inviteId + "/accept", student, Map.of()).andExpect(status().isCreated());
    }

    private User reload(User u) {
        return userRepository.findById(u.getId()).orElseThrow();
    }

    @Test
    void oneOfficialGroupPerSemesterButANewSemesterIsANewStart() throws Exception {
        User leaderA = user("r-leaderA", Role.STUDENT);
        UUID groupA = createGroup(leaderA, semA);
        User student = user("r-student", Role.STUDENT);
        accept(student, invite(reload(leaderA), groupA, student));

        // Same round: no second group, no new Apply, no new Invite.
        User leaderA2 = user("r-leaderA2", Role.STUDENT);
        UUID groupA2 = createGroup(leaderA2, semA);
        postJson("/api/v1/groups/" + groupA2 + "/applications", student, Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/groups/" + groupA2 + "/invites", reload(leaderA2), Map.of("userId", student.getId()))
                .andExpect(status().isConflict());
        postJson("/api/v1/groups", student, Map.of("semester", semA)).andExpect(status().isConflict());

        // Next round: the old membership does not block a fresh group.
        UUID groupB = createGroup(student, semB);
        assertThat(reload(student).getRole()).isEqualTo(Role.GROUP_LEADER);

        // Leaving the old group (Admin edit) does not strip the role they still need in the new one.
        User admin = user("r-admin", Role.ADMIN);
        UUID memberId = groupMemberRepository.findByGroupIdAndUserIdAndStatus(groupA, student.getId(), MemberStatus.ACTIVE)
                .orElseThrow().getId();
        mockMvc.perform(delete("/api/v1/groups/" + groupA + "/members/" + memberId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        assertThat(reload(student).getRole()).isEqualTo(Role.GROUP_LEADER);
        assertThat(groupB).isNotNull();
    }

    @Test
    void joiningCancelsOnlyTheOpenRequestsOfThatSemester() throws Exception {
        UUID a1 = createGroup(user("c-a1", Role.STUDENT), semA);
        User leaderA2 = user("c-a2", Role.STUDENT);
        UUID a2 = createGroup(leaderA2, semA);
        UUID b1 = createGroup(user("c-b1", Role.STUDENT), semB);
        User student = user("c-student", Role.STUDENT);
        UUID appA1 = apply(student, a1);
        UUID appB1 = apply(student, b1);

        accept(student, invite(reload(leaderA2), a2, student));

        assertThat(joinRequests.findById(appA1).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        assertThat(joinRequests.findById(appB1).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.PENDING);
    }

    @Test
    void afterTheFormationDeadlineStudentsNoLongerChangeRostersThemselves() throws Exception {
        String sem = "D-" + suffix;
        User admin = user("d-admin", Role.ADMIN);
        User leader = user("d-leader", Role.STUDENT);
        UUID group = createGroup(leader, sem);
        User member = user("d-member", Role.STUDENT);
        accept(member, invite(reload(leader), group, member));
        User invited = user("d-invited", Role.STUDENT);
        UUID openInvite = invite(reload(leader), group, invited);
        User applicant = user("d-applicant", Role.STUDENT);
        UUID openApply = apply(applicant, group);
        getAs("/api/v1/me/formation-window?semester=" + sem, member)
                .andExpect(jsonPath("$.open").value(true)).andExpect(jsonPath("$.maxOpenApplications").value(3));

        Instant past = Instant.now().minusSeconds(60);
        mockMvc.perform(put("/api/v1/semesters/" + sem + "/formation-deadline").header("Authorization", bearer(leader))
                .contentType("application/json").content("{\"deadline\":\"" + past + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/semesters/" + sem + "/formation-deadline").header("Authorization", bearer(admin))
                        .contentType("application/json").content("{\"deadline\":\"" + past + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ttlHours").value(48))
                .andExpect(jsonPath("$.formationDeadline").exists());
        getAs("/api/v1/me/formation-window?semester=" + sem, member).andExpect(jsonPath("$.open").value(false));

        postJson("/api/v1/groups", user("d-new", Role.STUDENT), Map.of("semester", sem)).andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/applications", user("d-late", Role.STUDENT), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/invites", reload(leader), Map.of("userId", user("d-x", Role.STUDENT).getId()))
                .andExpect(status().isConflict());
        postJson("/api/v1/applications/" + openApply + "/approve", reload(leader), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/invites/" + openInvite + "/accept", invited, Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/leave-requests", member, Map.of()).andExpect(status().isConflict());

        // Closing one's own open request is still fine, and the Admin still edits the roster.
        postJson("/api/v1/applications/" + openApply + "/withdraw", applicant, Map.of()).andExpect(status().isOk());
        postJson("/api/v1/groups/" + group + "/members", admin, Map.of("userId", user("d-admin-add", Role.STUDENT).getId()))
                .andExpect(status().isCreated());

        // Removing the cut-off reopens formation.
        mockMvc.perform(put("/api/v1/semesters/" + sem + "/formation-deadline").header("Authorization", bearer(admin))
                        .contentType("application/json").content("{\"deadline\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formationDeadline").doesNotExist());
        accept(invited, openInvite);
    }

    @Test
    void trainingDepartmentImportsCsvAndStudentsAreToldAboutTheirFlag() throws Exception {
        User admin = user("e-admin", Role.ADMIN);
        String ok = "e-ok-" + suffix + "@fpt.edu.vn";
        String flagged = "e-flag-" + suffix + "@fpt.edu.vn";
        String csv = "﻿Email;Họ tên;Đủ điều kiện;Lý do\n"
                + ok + ";Nguyễn Văn A;có;\n"
                + flagged + ";\"Trần, Thị B\";không;Nợ học phí\n"
                + "e-bad-" + suffix + "@fpt.edu.vn;C;maybe;\n";
        JsonNode result = body(mockMvc.perform(multipart("/api/v1/eligibility/import/file")
                        .file(new MockMultipartFile("file", "ds.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));
        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("flagged").asInt()).isEqualTo(1);
        assertThat(result.get("invalid")).hasSize(1);
        mockMvc.perform(multipart("/api/v1/eligibility/import/file")
                        .file(new MockMultipartFile("file", "ds.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(user("e-student", Role.STUDENT))))
                .andExpect(status().isForbidden());

        User student = userRepository.findByEmailIgnoreCase(flagged).orElseThrow();
        assertThat(student.getFullName()).isEqualTo("Trần, Thị B");
        getAs("/api/v1/eligibility/me", student)
                .andExpect(jsonPath("$.eligible").value(false)).andExpect(jsonPath("$.reason").value("Nợ học phí"));
        assertThat(countNotifications(student, "ELIGIBILITY_CHANGED")).isEqualTo(1);
        postJson("/api/v1/groups", student, Map.of("semester", semA)).andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/eligibility/" + student.getId()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"eligible\":true}")).andExpect(status().isOk());
        assertThat(countNotifications(student, "ELIGIBILITY_CHANGED")).isEqualTo(2);
        createGroup(reload(student), semA);
    }

    @Test
    void groupLosesTheApplicantsProfileOnceTheResultingInviteIsDeclined() throws Exception {
        User leader = user("y-leader", Role.STUDENT);
        UUID group = createGroup(leader, semA);
        User applicant = user("y-applicant", Role.STUDENT);
        mockMvc.perform(put("/api/v1/me/profile").header("Authorization", bearer(applicant)).contentType("application/json")
                .content("{\"bio\":\"Frontend\",\"skills\":\"React\"}")).andExpect(status().isOk());
        UUID app = apply(applicant, group);
        postJson("/api/v1/applications/" + app + "/approve", reload(leader), Map.of()).andExpect(status().isOk());

        // While the Invite it produced is open, the group still sees the applicant.
        getAs("/api/v1/groups/" + group + "/applications", reload(leader))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].student.skills").value("React"));
        getAs("/api/v1/groups/" + group + "/invites", reload(leader)).andExpect(jsonPath("$[0].student.bio").value("Frontend"));

        UUID inviteId = joinRequests.findBySourceApplicationIdAndType(app, JoinRequestType.INVITE).get(0).getId();
        postJson("/api/v1/invites/" + inviteId + "/decline", applicant, Map.of()).andExpect(status().isOk());

        getAs("/api/v1/groups/" + group + "/applications", reload(leader)).andExpect(jsonPath("$[0].student").doesNotExist());
        getAs("/api/v1/groups/" + group + "/invites", reload(leader))
                .andExpect(jsonPath("$[0].student.email").value(applicant.getEmail()))
                .andExpect(jsonPath("$[0].student.bio").doesNotExist())
                .andExpect(jsonPath("$[0].student.skills").doesNotExist());
        // The student always sees their own data.
        getAs("/api/v1/me/invites", applicant).andExpect(jsonPath("$[0].student.bio").value("Frontend"));
    }

    private int countNotifications(User as, String type) throws Exception {
        int n = 0;
        for (JsonNode node : body(getAs("/api/v1/notifications", as).andExpect(status().isOk())).get("content")) {
            if (type.equals(node.get("type").asText())) {
                n++;
            }
        }
        return n;
    }
}
