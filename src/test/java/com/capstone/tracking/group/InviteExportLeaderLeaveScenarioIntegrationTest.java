package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backend side of the manual test plan "Invite Student, xuất Excel, Leader yêu cầu rời nhóm". Each test carries the
 * plan's ID (INV-xx, EXP-xx, LEA-xx, REG-xx); steps that only the FE can check (button states, toasts) are listed in
 * docs/testing/BE-test-scenarios.md.
 *
 * <p>Fixture (fresh per test): Admin A; Leader L1 with group G1 of 3 members (L1, M1, M2); S1 eligible without a
 * group; S2 in another group G3; S3 not eligible; G2 already full (5 members). All groups are in semester SEM.</p>
 */
class InviteExportLeaderLeaveScenarioIntegrationTest extends WorkflowTestSupport {

    String sem;
    User admin;
    User l1;
    User m1;
    User m2;
    User s1;
    User s2;
    User s3;
    User l2;
    UUID g1;
    UUID g2;
    UUID g3;

    @BeforeEach
    void fixture() throws Exception {
        sem = "S-" + suffix;
        admin = user("adminA", Role.ADMIN);
        l1 = student("l1", "Lê Văn Lãnh Đạo");
        m1 = student("m1", "Trần Thị Một");
        m2 = student("m2", "Phạm Văn Hai");
        s1 = student("s1", "Nguyễn Văn Một");
        s2 = student("s2", "Đỗ Văn Hai");
        s3 = student("s3", "Võ Thị Ba");
        s3.setEligible(false);
        s3.setIneligibleReason("Thiếu tín chỉ");
        s3 = userRepository.save(s3);

        g1 = createGroup(l1);
        enroll(l1, g1, m1);
        enroll(l1, g1, m2);

        User l3 = student("l3", "Leader Ba");
        g3 = createGroup(l3);
        enroll(l3, g3, s2);

        l2 = student("l2", "Leader Hai");
        g2 = createGroup(l2);
        for (int i = 1; i <= 4; i++) {
            enroll(l2, g2, student("g2m" + i, "Thành viên " + i));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** MSSV in upper case, unique per test: e.g. S1AB12CD34. */
    String code(String tag) {
        return (tag + suffix).toUpperCase(Locale.ROOT);
    }

    User student(String tag, String fullName) {
        User u = user(tag, Role.STUDENT);
        u.setFullName(fullName + " " + suffix);
        u.setStudentCode(code(tag));
        return userRepository.save(u);
    }

    UUID createGroup(User leader) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", sem))
                .andExpect(status().isCreated())).get("id").asText());
    }

    UUID invite(User leader, UUID group, Object body) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups/" + group + "/invites", leader, body)
                .andExpect(status().isCreated())).get("id").asText());
    }

    void enroll(User leader, UUID group, User student) throws Exception {
        UUID id = invite(leader, group, Map.of("studentCode", student.getStudentCode()));
        postJson("/api/v1/invites/" + id + "/accept", student, Map.of()).andExpect(status().isCreated());
    }

    long activeMembers(UUID group) {
        return groupMemberRepository.countByGroupIdAndStatus(group, MemberStatus.ACTIVE);
    }

    boolean isMember(UUID group, User u) {
        return groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, u.getId(), MemberStatus.ACTIVE);
    }

    boolean isLeader(UUID group, User u) {
        return groupMemberRepository.existsByGroupIdAndUserIdAndIsLeaderTrueAndStatus(group, u.getId(), MemberStatus.ACTIVE);
    }

    List<JsonNode> notifications(User as, String type) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode n : body(getAs("/api/v1/notifications?size=200", as).andExpect(status().isOk())).get("content")) {
            if (type.equals(n.get("type").asText())) {
                found.add(n);
            }
        }
        return found;
    }

    ResultActions leave(User as, UUID group, Object body) throws Exception {
        return postJson("/api/v1/groups/" + group + "/leave-requests", as, body);
    }

    String leaderLeave(String reason) throws Exception {
        return body(leave(l1, g1, Map.of("reason", reason)).andExpect(status().isCreated())).get("id").asText();
    }

    byte[] export(String semester) throws Exception {
        return mockMvc.perform(get("/api/v1/reports/export").param("semester", semester)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    static List<List<String>> rows(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            row.forEach(c -> cells.add(c.getCellType() == CellType.NUMERIC
                    ? String.valueOf((long) c.getNumericCellValue()) : c.getStringCellValue()));
            rows.add(cells);
        }
        return rows;
    }

    // ================================================================== 2. Mời sinh viên bằng MSSV

    @Nested
    @DisplayName("2. Invite by MSSV")
    class Invite {

        @Test
        @DisplayName("INV-01 body {studentCode} only: invite created and listed for the group")
        void inv01() throws Exception {
            String id = body(postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("studentCode", s1.getStudentCode()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("INVITE"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.groupId").value(g1.toString()))
                    .andExpect(jsonPath("$.student.userId").value(s1.getId().toString()))
                    .andExpect(jsonPath("$.student.studentCode").value(s1.getStudentCode())))
                    .get("id").asText();
            assertThat(body(getAs("/api/v1/groups/" + g1 + "/invites", l1).andExpect(status().isOk()))
                    .findValuesAsText("id")).contains(id);
            assertThat(body(getAs("/api/v1/me/invites", s1).andExpect(status().isOk()))
                    .findValuesAsText("id")).contains(id);
        }

        @Test
        @DisplayName("INV-02 MSSV in lower case with spaces is still found (BE trims, ignores case)")
        void inv02() throws Exception {
            postJson("/api/v1/groups/" + g1 + "/invites", l1,
                    Map.of("studentCode", "  " + s1.getStudentCode().toLowerCase(Locale.ROOT) + "  "))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.student.userId").value(s1.getId().toString()));
        }

        @Test
        @DisplayName("INV-03 empty or blank MSSV: 400, no invite")
        void inv03() throws Exception {
            postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("studentCode", "   ")).andExpect(status().isBadRequest());
            postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of()).andExpect(status().isBadRequest());
            assertThat(body(getAs("/api/v1/groups/" + g1 + "/invites", l1)).findValuesAsText("id"))
                    .hasSize(2); // only the invites of M1 and M2 from the fixture
        }

        @Test
        @DisplayName("INV-04 unknown MSSV: 404 with a message, no invite")
        void inv04() throws Exception {
            postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("studentCode", "NOPE" + suffix))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("NOPE")));
        }

        @Test
        @DisplayName("INV-05 S2 already in another group: 409, S2 stays in G3")
        void inv05() throws Exception {
            postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("studentCode", s2.getStudentCode()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("already belongs to a group")));
            assertThat(isMember(g3, s2)).isTrue();
            assertThat(isMember(g1, s2)).isFalse();
        }

        @Test
        @DisplayName("INV-06 S3 not eligible: 403 NOT_ELIGIBLE with the reason")
        void inv06() throws Exception {
            postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("studentCode", s3.getStudentCode()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("NOT_ELIGIBLE"))
                    .andExpect(jsonPath("$.message").value(containsString("Thiếu tín chỉ")));
        }

        @Test
        @DisplayName("INV-07 inviting S1 again while pending: 409, still one pending invite")
        void inv07() throws Exception {
            invite(l1, g1, Map.of("studentCode", s1.getStudentCode()));
            postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("studentCode", s1.getStudentCode()))
                    .andExpect(status().isConflict());
            long pending = 0;
            for (JsonNode n : body(getAs("/api/v1/me/invites", s1))) {
                if ("PENDING".equals(n.get("status").asText())) {
                    pending++;
                }
            }
            assertThat(pending).isEqualTo(1);
        }

        @Test
        @DisplayName("INV-08 G2 already has 5 members: 409 (group size limit)")
        void inv08() throws Exception {
            assertThat(activeMembers(g2)).isEqualTo(5);
            postJson("/api/v1/groups/" + g2 + "/invites", l2, Map.of("studentCode", s1.getStudentCode()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("full")));
        }

        @Test
        @DisplayName("INV-09 (BE part) the same invite sent twice: the second is rejected, no duplicate")
        void inv09() throws Exception {
            Map<String, String> payload = Map.of("studentCode", s1.getStudentCode());
            int created = 0;
            for (int i = 0; i < 3; i++) {
                int code = postJson("/api/v1/groups/" + g1 + "/invites", l1, payload).andReturn().getResponse().getStatus();
                created += code == 201 ? 1 : 0;
                assertThat(code).isIn(201, 409);
            }
            assertThat(created).isEqualTo(1);
        }

        @Test
        @DisplayName("INV-10 S1 accepts: joins G1, member count 3 -> 4")
        void inv10() throws Exception {
            UUID id = invite(l1, g1, Map.of("studentCode", s1.getStudentCode()));
            assertThat(activeMembers(g1)).isEqualTo(3);
            postJson("/api/v1/invites/" + id + "/accept", s1, Map.of()).andExpect(status().isCreated());
            assertThat(isMember(g1, s1)).isTrue();
            getAs("/api/v1/groups/" + g1, l1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.members[?(@.userId=='" + s1.getId() + "')].studentCode")
                            .value(s1.getStudentCode()));
            assertThat(activeMembers(g1)).isEqualTo(4);
        }

        @Test
        @DisplayName("INV-11 L1 revokes a pending invite: WITHDRAWN, S1 cannot accept it")
        void inv11() throws Exception {
            UUID id = invite(l1, g1, Map.of("studentCode", s1.getStudentCode()));
            postJson("/api/v1/invites/" + id + "/revoke", l1, Map.of())
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
            postJson("/api/v1/invites/" + id + "/accept", s1, Map.of()).andExpect(status().isConflict());
            assertThat(isMember(g1, s1)).isFalse();
        }
    }

    // ================================================================== 3. Xuất file Excel

    @Nested
    @DisplayName("3. Excel export")
    class Export {

        @Test
        @DisplayName("EXP-01/02 GET /reports/export?semester=: .xlsx named Danh_sach_ghep_nhom_<HK>_yyyyMMdd_HHmmss.xlsx")
        void exp01and02() throws Exception {
            mockMvc.perform(get("/api/v1/reports/export").param("semester", sem).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .andExpect(header().string("Content-Disposition", matchesPattern(
                            "attachment; filename=\"Danh_sach_ghep_nhom_" + sem + "_\\d{8}_\\d{6}\\.xlsx\"")));
        }

        @Test
        @DisplayName("EXP-03/04 the file opens and has the sheets Danh_sach_nhom then Chua_co_nhom")
        void exp03and04() throws Exception {
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export(sem)))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                assertThat(wb.getSheetName(0)).isEqualTo("Danh_sach_nhom");
                assertThat(wb.getSheetName(1)).isEqualTo("Chua_co_nhom");
                assertThat(rows(wb.getSheet("Danh_sach_nhom")).get(0))
                        .containsSubsequence("Mã nhóm", "Học kỳ", "MSSV", "Họ tên", "Email", "Vai trò");
            }
        }

        @Test
        @DisplayName("EXP-05 G1: right semester, people, roles and member count; S1 is in Chua_co_nhom")
        void exp05() throws Exception {
            String g1Code = studentGroupRepository.findById(g1).orElseThrow().getGroupCode();
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export(sem)))) {
                List<List<String>> g1Rows = rows(wb.getSheet("Danh_sach_nhom")).stream()
                        .filter(r -> r.contains(g1Code)).toList();
                assertThat(g1Rows).hasSize(3);
                assertThat(g1Rows).allSatisfy(r -> {
                    assertThat(r.get(2)).isEqualTo(sem);   // Học kỳ
                    assertThat(r.get(9)).isEqualTo("3");   // Số thành viên
                });
                assertThat(g1Rows.get(0)).contains(l1.getStudentCode(), l1.getFullName(), "Leader");
                assertThat(g1Rows.stream().filter(r -> r.contains("Leader"))).hasSize(1);
                assertThat(g1Rows).anyMatch(r -> r.contains(m1.getStudentCode()) && r.contains("Thành viên"));
                assertThat(rows(wb.getSheet("Chua_co_nhom"))).anyMatch(r -> r.contains(s1.getStudentCode()))
                        .noneMatch(r -> r.contains(m1.getStudentCode()));
            }
        }

        @Test
        @DisplayName("EXP-06 leading zero MSSV, Vietnamese names and formula-like text stay text")
        void exp06() throws Exception {
            User zero = user("zero", Role.STUDENT);
            zero.setStudentCode("0" + suffix.replaceAll("[^0-9]", "1"));
            zero.setFullName("=HYPERLINK(\"x\") Nguyễn Thị Ánh Tuyết");
            zero = userRepository.save(zero);
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export(sem)))) {
                Row row = null;
                for (Row r : wb.getSheet("Chua_co_nhom")) {
                    if (r.getCell(3) != null && zero.getEmail().equals(r.getCell(3).getStringCellValue())) {
                        row = r;
                    }
                }
                assertThat(row).isNotNull();
                Cell mssv = row.getCell(1);
                Cell name = row.getCell(2);
                assertThat(mssv.getCellType()).isEqualTo(CellType.STRING);
                assertThat(mssv.getStringCellValue()).isEqualTo(zero.getStudentCode()).startsWith("0");
                assertThat(name.getCellType()).isEqualTo(CellType.STRING);
                assertThat(name.getStringCellValue()).isEqualTo(zero.getFullName());
                // Group sheet: the Vietnamese leader name round-trips unchanged.
                assertThat(rows(wb.getSheet("Danh_sach_nhom"))).anyMatch(r -> r.contains(l1.getFullName()));
            }
        }

        @Test
        @DisplayName("EXP-07 semester without data: 200, a well-formed file whose group sheet only has the header")
        void exp07() throws Exception {
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export("EMPTY-" + suffix)))) {
                assertThat(rows(wb.getSheet("Danh_sach_nhom"))).hasSize(1);
                assertThat(rows(wb.getSheet("Chua_co_nhom")).get(0)).contains("MSSV");
            }
        }

        @Test
        @DisplayName("EXP-11 no / invalid / expired token: 401 JSON, never a file; non-Admin: 403")
        void exp11() throws Exception {
            mockMvc.perform(get("/api/v1/reports/export").param("semester", sem))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Content-Type", containsString("application/json")));
            mockMvc.perform(get("/api/v1/reports/export").param("semester", sem)
                            .header("Authorization", "Bearer expired.or.forged.token"))
                    .andExpect(status().isUnauthorized());
            getAs("/api/v1/reports/export?semester=" + sem, l1).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("EXP-12 member change then export again: the new file reflects it")
        void exp12() throws Exception {
            enroll(l1, g1, s1);
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export(sem)))) {
                assertThat(rows(wb.getSheet("Danh_sach_nhom"))).anyMatch(r -> r.contains(s1.getStudentCode()) && r.contains("4"));
                assertThat(rows(wb.getSheet("Chua_co_nhom"))).noneMatch(r -> r.contains(s1.getStudentCode()));
            }
        }
    }

    // ================================================================== 4. Leader yêu cầu rời nhóm

    @Nested
    @DisplayName("4. Leader asks to leave")
    class LeaderLeave {

        @Test
        @DisplayName("LEA-01/02 L1 sends a reason: PENDING leader request; L1 stays Leader and member meanwhile")
        void lea01and02() throws Exception {
            leave(l1, g1, Map.of("reason", "Đi du học"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.leaderRequest").value(true))
                    .andExpect(jsonPath("$.groupId").value(g1.toString()))
                    .andExpect(jsonPath("$.userId").value(l1.getId().toString()))
                    .andExpect(jsonPath("$.reason").value("Đi du học"));
            assertThat(isLeader(g1, l1)).isTrue();
            assertThat(activeMembers(g1)).isEqualTo(3);
        }

        @Test
        @DisplayName("LEA-03/04 Admin gets LEADER_LEAVE_REQUESTED naming group, sender and reason (not LEAVE_REQUESTED)")
        void lea03and04() throws Exception {
            String id = leaderLeave("Đi du học");
            String g1Code = studentGroupRepository.findById(g1).orElseThrow().getGroupCode();
            List<JsonNode> mine = notifications(admin, "LEADER_LEAVE_REQUESTED").stream()
                    .filter(n -> id.equals(n.get("entityId").asText())).toList();
            assertThat(mine).hasSize(1);
            JsonNode n = mine.get(0);
            assertThat(n.get("groupId").asText()).isEqualTo(g1.toString());
            assertThat(n.get("message").asText()).contains(l1.getFullName()).contains(g1Code).contains("Leader");
            assertThat(n.get("details").asText()).isEqualTo("Đi du học");
            assertThat(notifications(admin, "LEAVE_REQUESTED")).noneMatch(x -> id.equals(x.get("entityId").asText()));
            // The members are not asked to decide it.
            assertThat(notifications(m1, "LEADER_LEAVE_REQUESTED")).isEmpty();
        }

        @Test
        @DisplayName("LEA-05 no reason / blank reason: 400, nothing created")
        void lea05() throws Exception {
            leave(l1, g1, Map.of("reason", "   ")).andExpect(status().isBadRequest());
            leave(l1, g1, Map.of()).andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/groups/" + g1 + "/leave-requests").header("Authorization", bearer(l1)))
                    .andExpect(status().isBadRequest());
            assertThat(body(getAs("/api/v1/groups/" + g1 + "/leave-requests", l1))).isEmpty();
        }

        @Test
        @DisplayName("LEA-06 reason over 1000 characters: 400; exactly 1000 is accepted")
        void lea06() throws Exception {
            leave(l1, g1, Map.of("reason", "a".repeat(1001))).andExpect(status().isBadRequest());
            leave(l1, g1, Map.of("reason", "a".repeat(1000))).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("LEA-07 sending again while pending: 409, one request and one Admin notification")
        void lea07() throws Exception {
            String id = leaderLeave("Lý do 1");
            leave(l1, g1, Map.of("reason", "Lý do 2")).andExpect(status().isConflict());
            assertThat(body(getAs("/api/v1/groups/" + g1 + "/leave-requests", l1))).hasSize(1);
            assertThat(notifications(admin, "LEADER_LEAVE_REQUESTED").stream()
                    .filter(n -> g1.toString().equals(n.get("groupId").asText()))).hasSize(1)
                    .allMatch(n -> id.equals(n.get("entityId").asText()));
        }

        @Test
        @DisplayName("LEA-08 after re-login the request is still there, PENDING (group list and Admin inbox)")
        void lea08() throws Exception {
            String id = leaderLeave("Đi du học");
            // A new token = signing in again.
            JsonNode list = body(getAs("/api/v1/groups/" + g1 + "/leave-requests", l1).andExpect(status().isOk()));
            assertThat(list.get(0).get("id").asText()).isEqualTo(id);
            assertThat(list.get(0).get("status").asText()).isEqualTo("PENDING");
            assertThat(body(getAs("/api/v1/leave-requests?status=PENDING&leaderOnly=true", admin))
                    .findValuesAsText("id")).contains(id);
        }

        @Test
        @DisplayName("LEA-09 L1 cannot approve or reject their own request: 403")
        void lea09() throws Exception {
            String id = leaderLeave("Đi du học");
            postJson("/api/v1/leave-requests/" + id + "/approve", l1, Map.of()).andExpect(status().isForbidden());
            postJson("/api/v1/leave-requests/" + id + "/reject", l1, Map.of()).andExpect(status().isForbidden());
            assertThat(isLeader(g1, l1)).isTrue();
        }

        @Test
        @DisplayName("LEA-10 Admin rejects: REJECTED, L1 still Leader, L1 is told")
        void lea10() throws Exception {
            String id = leaderLeave("Đi du học");
            postJson("/api/v1/leave-requests/" + id + "/reject", admin, Map.of())
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
            assertThat(isLeader(g1, l1)).isTrue();
            assertThat(notifications(l1, "LEAVE_DECIDED"))
                    .anyMatch(n -> id.equals(n.get("entityId").asText()) && n.get("message").asText().contains("từ chối"));
            postJson("/api/v1/leave-requests/" + id + "/approve", admin, Map.of()).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("LEA-11/12 Admin approves naming M2: exactly one Leader (M2); L1 leaves the group and becomes STUDENT")
        void lea11and12() throws Exception {
            String id = leaderLeave("Đi du học");
            postJson("/api/v1/leave-requests/" + id + "/approve", admin, Map.of("newLeaderUserId", m2.getId()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(groupMemberRepository.findByGroupIdAndStatus(g1, MemberStatus.ACTIVE).stream()
                    .filter(GroupMember::isLeader).map(m -> m.getUser().getId())).containsExactly(m2.getId());
            assertThat(isMember(g1, l1)).isFalse();
            assertThat(activeMembers(g1)).isEqualTo(2);
            assertThat(userRepository.findById(l1.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
            assertThat(userRepository.findById(m2.getId()).orElseThrow().getRole()).isEqualTo(Role.GROUP_LEADER);
            assertThat(notifications(m2, "LEADER_CHANGED")).isNotEmpty();
            assertThat(notifications(l1, "LEAVE_DECIDED")).anyMatch(n -> id.equals(n.get("entityId").asText()));
            // The new leader can act for the group, the former one cannot.
            postJson("/api/v1/groups/" + g1 + "/invites", m2, Map.of("studentCode", s1.getStudentCode()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("LEA-12 changing the Leader alone (PUT /leader) keeps L1 in the group and the request pending")
        void lea12ReplaceLeaderOnly() throws Exception {
            String id = leaderLeave("Đi du học");
            mockMvc.perform(put("/api/v1/groups/" + g1 + "/leader").header("Authorization", bearer(admin))
                    .contentType("application/json").content("{\"userId\":\"" + m1.getId() + "\"}"))
                    .andExpect(status().isOk());
            assertThat(isLeader(g1, m1)).isTrue();
            assertThat(isMember(g1, l1)).isTrue();
            assertThat(isLeader(g1, l1)).isFalse();
            assertThat(body(getAs("/api/v1/leave-requests?status=PENDING", admin)).findValuesAsText("id")).contains(id);
        }

        @Test
        @DisplayName("LEA-13 expired / invalid token: 401, nothing created")
        void lea13() throws Exception {
            mockMvc.perform(post("/api/v1/groups/" + g1 + "/leave-requests")
                            .header("Authorization", "Bearer expired.or.forged.token")
                            .contentType("application/json").content("{\"reason\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
            assertThat(body(getAs("/api/v1/groups/" + g1 + "/leave-requests", l1))).isEmpty();
        }

        @Test
        @DisplayName("LEA-14 account outside G1 (S1, L2): 403, no request and no Admin notification")
        void lea14() throws Exception {
            int before = notifications(admin, "LEADER_LEAVE_REQUESTED").size();
            leave(s1, g1, Map.of("reason", "x")).andExpect(status().isForbidden());
            leave(l2, g1, Map.of("reason", "x")).andExpect(status().isForbidden());
            assertThat(body(getAs("/api/v1/groups/" + g1 + "/leave-requests", l1))).isEmpty();
            assertThat(notifications(admin, "LEADER_LEAVE_REQUESTED")).hasSize(before);
        }
    }

    // ================================================================== 5. Hồi quy thành viên thường

    @Nested
    @DisplayName("5. Regression: ordinary member leaves")
    class Regression {

        @Test
        @DisplayName("REG-01..03 member sends {reason}; Leader is notified, approves; member removed, count 3 -> 2")
        void approve() throws Exception {
            String id = body(leave(m1, g1, Map.of("reason", "Bận đi làm")).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leaderRequest").value(false))).get("id").asText();
            assertThat(notifications(l1, "LEAVE_REQUESTED")).anyMatch(n -> id.equals(n.get("entityId").asText()));
            assertThat(notifications(admin, "LEADER_LEAVE_REQUESTED")).noneMatch(n -> id.equals(n.get("entityId").asText()));
            postJson("/api/v1/leave-requests/" + id + "/approve", l1, Map.of())
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(isMember(g1, m1)).isFalse();
            assertThat(activeMembers(g1)).isEqualTo(2);
        }

        @Test
        @DisplayName("REG-01 member may still leave without a reason (unchanged contract)")
        void memberWithoutReason() throws Exception {
            leave(m1, g1, Map.of()).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("REG-04 Leader rejects another request: member stays")
        void reject() throws Exception {
            String id = body(leave(m2, g1, Map.of("reason", "x")).andExpect(status().isCreated())).get("id").asText();
            postJson("/api/v1/leave-requests/" + id + "/reject", l1, Map.of())
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
            assertThat(isMember(g1, m2)).isTrue();
            assertThat(activeMembers(g1)).isEqualTo(3);
        }

        @Test
        @DisplayName("REG-05a locked group: member cannot ask (409); a pending request cannot be approved by the Leader")
        void locked() throws Exception {
            String id = body(leave(m2, g1, Map.of("reason", "x")).andExpect(status().isCreated())).get("id").asText();
            postJson("/api/v1/groups/" + g1 + "/lock", admin, Map.of()).andExpect(status().isOk());
            leave(m1, g1, Map.of("reason", "x")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("locked")));
            postJson("/api/v1/leave-requests/" + id + "/approve", l1, Map.of()).andExpect(status().isConflict());
            // The Admin still can.
            postJson("/api/v1/leave-requests/" + id + "/approve", admin, Map.of()).andExpect(status().isOk());
            assertThat(isMember(g1, m2)).isFalse();
        }

        @Test
        @DisplayName("REG-05b formation deadline passed: member cannot ask (409); the Leader's request still goes to Admin")
        void deadlinePassed() throws Exception {
            mockMvc.perform(put("/api/v1/semesters/" + sem + "/formation-deadline").header("Authorization", bearer(admin))
                    .contentType("application/json").content("{\"deadline\":\"2020-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk());
            leave(m1, g1, Map.of("reason", "x")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("closed")));
            leave(l1, g1, Map.of("reason", "Đi du học")).andExpect(status().isCreated());
        }
    }
}
