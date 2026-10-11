package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reported BE issues: invite by MSSV shows the student code, Admin replaces the Leader, the Leader's request to leave
 * reaches the Admins, the imported students can be listed, and the handover list exports to Excel.
 */
class LeaderLeaveRequestAndAdminFixesIntegrationTest extends WorkflowTestSupport {

    private User student(String name, String code) {
        User u = user(name, Role.STUDENT);
        u.setStudentCode(code);
        return userRepository.save(u);
    }

    private UUID createGroup(User leader) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", "S-" + suffix))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private void addMember(User leader, UUID group, User student) throws Exception {
        UUID invite = UUID.fromString(body(postJson("/api/v1/groups/" + group + "/invites", leader,
                Map.of("studentCode", student.getStudentCode())).andExpect(status().isCreated())).get("id").asText());
        postJson("/api/v1/invites/" + invite + "/accept", student, Map.of()).andExpect(status().isCreated());
    }

    private boolean hasNotification(User as, String type) throws Exception {
        for (JsonNode n : body(getAs("/api/v1/notifications", as).andExpect(status().isOk())).get("content")) {
            if (type.equals(n.get("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean isLeader(UUID group, User user) {
        return groupMemberRepository.existsByGroupIdAndUserIdAndIsLeaderTrueAndStatus(group, user.getId(), MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("Invite by studentCode returns the student's MSSV")
    void inviteByStudentCodeReturnsIt() throws Exception {
        User leader = student("iv-leader", "IVL" + suffix);
        UUID group = createGroup(leader);
        User s = student("iv-s", "IVS" + suffix);
        postJson("/api/v1/groups/" + group + "/invites", leader, Map.of("studentCode", s.getStudentCode()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.student.userId").value(s.getId().toString()))
                .andExpect(jsonPath("$.student.studentCode").value(s.getStudentCode()));
    }

    @Test
    @DisplayName("Admin changes the Leader by member id, MSSV, 'add as leader' and the group edit form")
    void adminChangesLeaderEveryWay() throws Exception {
        User admin = user("cl-admin", Role.ADMIN);
        User leader = student("cl-leader", "CLL" + suffix);
        UUID group = createGroup(leader);
        User a = student("cl-a", "CLA" + suffix);
        User b = student("cl-b", "CLB" + suffix);
        User c = student("cl-c", "CLC" + suffix);
        addMember(leader, group, a);
        addMember(leader, group, b);
        String url = "/api/v1/groups/" + group + "/leader";

        // By membership id (the "id" of the member list).
        UUID memberA = groupMemberRepository.findByGroupIdAndUserId(group, a.getId()).orElseThrow().getId();
        mockMvc.perform(put(url).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"userId\":\"" + memberA + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(a.getId().toString()));
        assertThat(isLeader(group, a)).isTrue();
        assertThat(isLeader(group, leader)).isFalse();
        assertThat(userRepository.findById(leader.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);

        // By MSSV.
        mockMvc.perform(put(url).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"studentCode\":\"" + b.getStudentCode() + "\"}")).andExpect(status().isOk());
        assertThat(isLeader(group, b)).isTrue();
        assertThat(isLeader(group, a)).isFalse();

        // "Add member as leader" on an existing member promotes them instead of failing with 409.
        postJson("/api/v1/groups/" + group + "/members", admin, Map.of("userId", leader.getId(), "isLeader", true))
                .andExpect(status().isCreated());
        assertThat(isLeader(group, leader)).isTrue();

        // A new student added as leader replaces the current one.
        postJson("/api/v1/groups/" + group + "/members", admin, Map.of("studentCode", c.getStudentCode(), "isLeader", true))
                .andExpect(status().isCreated());
        assertThat(isLeader(group, c)).isTrue();
        assertThat(isLeader(group, leader)).isFalse();

        // The group edit form, without a status.
        mockMvc.perform(put("/api/v1/groups/" + group).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"leaderUserId\":\"" + a.getId() + "\"}"))
                .andExpect(status().isOk());
        assertThat(isLeader(group, a)).isTrue();
        assertThat(groupMemberRepository.findByGroupIdAndStatus(group, MemberStatus.ACTIVE)
                .stream().filter(GroupMember::isLeader)).hasSize(1);
        assertThat(hasNotification(a, "LEADER_CHANGED")).isTrue();
    }

    @Test
    @DisplayName("Leader asks to leave: Admins are notified and decide; the named member takes over")
    void leaderLeaveRequestGoesToAdmin() throws Exception {
        User admin = user("ll-admin", Role.ADMIN);
        User leader = student("ll-leader", "LLL" + suffix);
        UUID group = createGroup(leader);
        User a = student("ll-a", "LLA" + suffix);
        User b = student("ll-b", "LLB" + suffix);
        addMember(leader, group, a);
        addMember(leader, group, b);
        // Even a Locked roster: the Admin decides.
        postJson("/api/v1/groups/" + group + "/lock", admin, Map.of()).andExpect(status().isOk());

        String id = body(postJson("/api/v1/groups/" + group + "/leave-requests", leader, Map.of("reason", "Đi du học"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leaderRequest").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asText();
        assertThat(hasNotification(admin, "LEADER_LEAVE_REQUESTED")).isTrue();

        // Admin inbox.
        JsonNode inbox = body(getAs("/api/v1/leave-requests?status=PENDING&leaderOnly=true", admin).andExpect(status().isOk()));
        assertThat(inbox.findValuesAsText("id")).contains(id);
        getAs("/api/v1/leave-requests", leader).andExpect(status().isForbidden());

        // The leader cannot approve their own request.
        postJson("/api/v1/leave-requests/" + id + "/approve", leader, Map.of()).andExpect(status().isForbidden());

        postJson("/api/v1/leave-requests/" + id + "/approve", admin, Map.of("newLeaderUserId", b.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, leader.getId(), MemberStatus.ACTIVE)).isFalse();
        assertThat(isLeader(group, b)).isTrue();
        assertThat(userRepository.findById(leader.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        assertThat(hasNotification(leader, "LEAVE_DECIDED")).isTrue();
    }

    @Test
    @DisplayName("Leader leaves without a named successor: the earliest-joined member takes over; Admin may reject")
    void leaderLeaveWithoutSuccessorPromotesEarliestMember() throws Exception {
        User admin = user("ls-admin", Role.ADMIN);
        User leader = student("ls-leader", "LSL" + suffix);
        UUID group = createGroup(leader);
        User a = student("ls-a", "LSA" + suffix);
        User b = student("ls-b", "LSB" + suffix);
        addMember(leader, group, a);
        addMember(leader, group, b);

        String first = body(postJson("/api/v1/groups/" + group + "/leave-requests", leader, Map.of("reason", "Chuyển trường"))
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/leave-requests/" + first + "/reject", admin, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(isLeader(group, leader)).isTrue();

        String second = body(postJson("/api/v1/groups/" + group + "/leave-requests", leader, Map.of("reason", "Chuyển trường"))
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/leave-requests/" + second + "/approve", admin, Map.of()).andExpect(status().isOk());
        assertThat(isLeader(group, a)).isTrue();
        assertThat(groupMemberRepository.findByGroupIdAndStatus(group, MemberStatus.ACTIVE)).hasSize(2);
    }

    @Test
    @DisplayName("Import returns each student's detail and the imported students can be listed")
    void importedStudentsAreListed() throws Exception {
        User admin = user("im-admin", Role.ADMIN);
        User existing = user("im-old", Role.STUDENT);
        String fresh = "im-new-" + suffix + "@fpt.edu.vn";
        List<Map<String, Object>> students = List.of(
                Map.of("email", fresh, "fullName", "Nguyen Van Moi " + suffix, "eligible", true, "studentCode", "IMN" + suffix),
                Map.of("email", existing.getEmail(), "fullName", "x", "eligible", false, "reason", "No credits"));

        postJson("/api/v1/eligibility/import", admin, Map.of("students", students))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.students.length()").value(2))
                .andExpect(jsonPath("$.students[0].email").value(fresh))
                .andExpect(jsonPath("$.students[0].studentCode").value("IMN" + suffix))
                .andExpect(jsonPath("$.students[0].action").value("CREATED"))
                .andExpect(jsonPath("$.students[1].action").value("UPDATED"))
                .andExpect(jsonPath("$.students[1].eligible").value(false));

        getAs("/api/v1/eligibility/students?q=" + suffix + "&size=50", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.email=='" + fresh + "')].importedAt").isNotEmpty());
        getAs("/api/v1/eligibility/students?eligible=false&q=" + suffix, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(existing.getEmail()));
        getAs("/api/v1/eligibility/students", existing).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Handover export: an Excel file with Danh_sach_nhom and Chua_co_nhom")
    void handoverExport() throws Exception {
        User admin = user("ex-admin", Role.ADMIN);
        User leader = student("ex-leader", "EXL" + suffix);
        UUID group = createGroup(leader);
        User member = student("ex-m", "EXM" + suffix);
        addMember(leader, group, member);
        User loner = student("ex-loner", "EXN" + suffix);
        String code = studentGroupRepository.findById(group).orElseThrow().getGroupCode();

        byte[] file = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/reports/handover/export?semester=S-" + suffix).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Danh_sach_ghep_nhom_S-")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Danh_sach_nhom");
            assertThat(workbook.getSheetName(1)).isEqualTo("Chua_co_nhom");
            List<String> groupRows = text(workbook.getSheet("Danh_sach_nhom"));
            assertThat(groupRows).anyMatch(r -> r.contains(code) && r.contains("EXL" + suffix) && r.contains("Leader"));
            assertThat(groupRows).anyMatch(r -> r.contains(code) && r.contains("EXM" + suffix));
            assertThat(groupRows).noneMatch(r -> r.contains("EXN" + suffix));
            List<String> lonerRows = text(workbook.getSheet("Chua_co_nhom"));
            assertThat(lonerRows).anyMatch(r -> r.contains(loner.getEmail()) && r.contains("EXN" + suffix));
            assertThat(lonerRows).noneMatch(r -> r.contains(member.getEmail()));
        }
        getAs("/api/v1/reports/handover/export", leader).andExpect(status().isForbidden());
    }

    private static List<String> text(Sheet sheet) {
        List<String> rows = new java.util.ArrayList<>();
        for (Row row : sheet) {
            StringBuilder sb = new StringBuilder();
            row.forEach(cell -> sb.append(cell.toString()).append('|'));
            rows.add(sb.toString());
        }
        return rows;
    }
}
