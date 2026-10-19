package com.capstone.tracking.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.team.JoinRequest;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/** The deadline (3.4), the Moderator's coordination (7.1), finalizing (7.2), statistics (7.3) and the export (10). */
class DeadlineAndFinalizeIntegrationTest extends TestSupport {

    @Test
    void theDeadlineCancelsRequestsDissolvesSmallGroupsAndTurnsLeaveRequestsIntoComplaints() throws Exception {
        World w = world(8, 0);
        List<User> s = w.students();
        UUID big = createGroup(w, s.get(0), "Big");
        join(big, s.get(0), s.get(1));
        join(big, s.get(0), s.get(2));
        UUID small = createGroup(w, s.get(3), "Small");
        join(small, s.get(3), s.get(4));
        UUID pending = id(postJson("/api/v1/groups/" + big + "/applications", s.get(5), Map.of()).andExpect(status().isCreated()));
        postJson("/api/v1/groups/" + big + "/lock", s.get(0), Map.of()).andExpect(status().isOk());
        String leave = body(postJson("/api/v1/groups/" + big + "/leave", s.get(2), Map.of()).andExpect(status().isOk()))
                .get("request").get("id").asText();

        FormationScope scope = passDeadline(w.scope());
        assertThat(scope.getPhase()).isEqualTo(ScopePhase.LOCKED);
        JoinRequest request = joinRequestRepository.findById(pending).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(JoinRequest.Status.CANCELLED);
        assertThat(request.getCancelReason()).isEqualTo(JoinRequest.CancelReason.DEADLINE);
        getAs("/api/v1/groups/" + small, w.moderator()).andExpect(jsonPath("$.dissolvedReason").value("TOO_SMALL_AT_DEADLINE"));
        getAs("/api/v1/subjects/" + w.subjectId() + "/complaints", w.moderator())
                .andExpect(jsonPath("$[0].id").value(leave)).andExpect(jsonPath("$[0].complaint").value(true));

        // After the deadline nobody acts by themselves: no new group, no leaving, no Leader rights.
        postJson("/api/v1/subjects/" + w.subjectId() + "/groups", s.get(6), Map.of("name", "Late"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SCOPE_NOT_OPEN"));
        postJson("/api/v1/groups/" + big + "/unlock", s.get(0), Map.of()).andExpect(status().isConflict());
        deleteAs("/api/v1/groups/" + big + "/members/" + s.get(1).getId(), s.get(0)).andExpect(status().isConflict());
        JsonNode afterDeadline = body(postJson("/api/v1/groups/" + big + "/leave", s.get(1), Map.of()).andExpect(status().isOk()));
        assertThat(afterDeadline.get("request").get("status").asText()).isEqualTo("PENDING_MODERATOR");
    }

    @Test
    void theModeratorArrangesStudentsFinalizesAndReopens() throws Exception {
        World w = world(9, 0);
        List<User> s = w.students();
        UUID a = createGroup(w, s.get(0), "A");
        join(a, s.get(0), s.get(1));
        join(a, s.get(0), s.get(2));
        UUID b = createGroup(w, s.get(3), "B");
        join(b, s.get(3), s.get(4));
        join(b, s.get(3), s.get(5));
        passDeadline(w.scope());
        // s6, s7, s8 have no group. Coordination is only for this semester's Moderator.
        User stranger = user("stranger", com.capstone.tracking.user.Role.LECTURER);
        postJson("/api/v1/groups/" + a + "/moderation/add", stranger, Map.of("userId", s.get(6).getId()))
                .andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + a + "/moderation/add", w.moderator(), Map.of("userId", s.get(6).getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(4));
        // Move a Leader: the old group picks the earliest member as its Leader (6.5).
        postJson("/api/v1/groups/" + a + "/moderation/move", w.moderator(), Map.of("userId", s.get(3).getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(5));
        getAs("/api/v1/groups/" + b, w.moderator()).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.leaderId").value(s.get(4).getId().toString()));
        // B is now below 3: finalizing is blocked until it is fixed.
        getAs("/api/v1/scopes/" + w.scope().getId() + "/finalize-check", w.moderator())
                .andExpect(jsonPath("$.canFinalize").value(false))
                .andExpect(jsonPath("$.groupsUnderMinimum.length()").value(1));
        postJson("/api/v1/scopes/" + w.scope().getId() + "/finalize", w.moderator(), Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CANNOT_FINALIZE"));
        postJson("/api/v1/groups/" + b + "/moderation/add", w.moderator(), Map.of("userId", s.get(7).getId()))
                .andExpect(status().isOk());
        // A new group for the last student is too small; dissolving it in bulk puts them back without a group.
        UUID c = id(postJson("/api/v1/scopes/" + w.scope().getId() + "/groups", w.moderator(),
                Map.of("name", "C", "leaderUserId", s.get(8).getId())).andExpect(status().isCreated()));
        getAs("/api/v1/subjects/" + w.subjectId() + "/groups?maxSize=2", w.moderator())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(c.toString()));
        postJson("/api/v1/moderation/dissolve", w.moderator(), Map.of("groupIds", List.of(c), "reason", "Dưới 3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dissolved").value(1));

        JsonNode check = body(getAs("/api/v1/scopes/" + w.scope().getId() + "/finalize-check", w.moderator()));
        assertThat(check.get("canFinalize").asBoolean()).isTrue();
        assertThat(check.get("warnings").toString()).contains("1 eligible or undecided student(s) have no group");
        postJson("/api/v1/scopes/" + w.scope().getId() + "/finalize", w.moderator(), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FINALIZED"));
        Enrollment finalized = enrollmentRepository.findBySubjectIdAndUserId(w.subjectId(), s.get(0).getId()).orElseThrow();
        Enrollment notFinalized = enrollmentRepository.findBySubjectIdAndUserId(w.subjectId(), s.get(8).getId()).orElseThrow();
        assertThat(finalized.isFinalized()).isTrue();
        assertThat(notFinalized.isFinalized()).isFalse();

        // Finalized: view and export only, except transferring the Leader inside a group (recorded).
        postJson("/api/v1/groups/" + a + "/moderation/remove", w.moderator(), Map.of("userId", s.get(6).getId(), "reason", "x"))
                .andExpect(status().isConflict());
        String transfer = body(postJson("/api/v1/groups/" + a + "/leader-transfers", s.get(0),
                Map.of("toUserId", s.get(1).getId())).andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/leader-transfers/" + transfer + "/accept", s.get(1), Map.of()).andExpect(status().isOk());
        getAs("/api/v1/scopes/" + w.scope().getId(), w.moderator())
                .andExpect(jsonPath("$.lastChangeAfterFinalize", Matchers.containsString("Chuyển Leader")));

        // Reopening needs a reason, lifts the flags and keeps the time it was finalized.
        postJson("/api/v1/scopes/" + w.scope().getId() + "/reopen", w.admin(), Map.of("reason", ""))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/scopes/" + w.scope().getId() + "/reopen", w.admin(), Map.of("reason", "Sai danh sách"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("LOCKED"))
                .andExpect(jsonPath("$.previousFinalizedAt").isNotEmpty());
        assertThat(enrollmentRepository.findById(finalized.getId()).orElseThrow().isFinalized()).isFalse();
    }

    @Test
    void anExtensionReopensFormationWithANewDeadline() throws Exception {
        World w = world(2, 0);
        passDeadline(w.scope());
        postJson("/api/v1/scopes/" + w.scope().getId() + "/extend", w.moderator(),
                Map.of("deadline", Instant.now().plus(Duration.ofHours(5)).toString())).andExpect(status().isBadRequest());
        postJson("/api/v1/scopes/" + w.scope().getId() + "/extend", w.moderator(),
                Map.of("deadline", Instant.now().plus(Duration.ofDays(3)).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("OPEN"))
                .andExpect(jsonPath("$.ownDeadline").value(true));
        createGroup(w, w.students().get(0), "After extension");
    }

    @Test
    void statisticsAndTheExcelFileAgree() throws Exception {
        World w = world(5, 2);
        List<User> s = w.students();
        UUID a = createGroup(w, s.get(0), "Stats A");
        join(a, s.get(0), s.get(1));
        join(a, s.get(0), s.get(2));
        createGroup(w, s.get(3), "Stats B");
        UUID enrollment = enrollmentRepository.findBySubjectIdAndUserId(w.subjectId(), s.get(4).getId()).orElseThrow().getId();
        putJson("/api/v1/enrollments/" + enrollment + "/eligibility", w.moderator(),
                Map.of("eligibility", "INELIGIBLE", "reason", "Nợ học phí")).andExpect(status().isOk());

        JsonNode stats = body(getAs("/api/v1/semesters/" + w.code() + "/statistics?subjectId=" + w.subjectId()
                + "&majorId=" + w.major().getId(), w.moderator()).andExpect(status().isOk()));
        JsonNode total = stats.get("total");
        assertThat(total.get("totalStudents").asInt()).isEqualTo(5);
        assertThat(total.get("withGroup").asInt()).isEqualTo(4);
        assertThat(total.get("withoutGroup").asInt()).isEqualTo(1);
        assertThat(total.get("ineligible").asInt()).isEqualTo(1);
        assertThat(total.get("groupsBySize").get("3").asInt()).isEqualTo(1);
        assertThat(total.get("groupsBySize").get("1").asInt()).isEqualTo(1);
        assertThat(total.get("groupsUnderMinimum").asInt()).isEqualTo(1);
        // Students cannot see statistics.
        getAs("/api/v1/semesters/" + w.code() + "/statistics", s.get(0)).andExpect(status().isForbidden());

        byte[] file = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/semesters/" + w.code() + "/export?subjectId=" + w.subjectId())
                        .header("Authorization", bearer(w.moderator())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString(
                        "Danh_sach_ghep_nhom_" + w.code() + "_CAP_TatCa_")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet groups = wb.getSheet("Danh sách nhóm");
            Sheet without = wb.getSheet("Chưa có nhóm");
            assertThat(cell(groups.getRow(4))).contains("Chưa chốt");
            int groupRows = dataRows(groups);
            int withoutRows = dataRows(without);
            assertThat(groupRows).isEqualTo(4); // A has 3 members, B has 1
            assertThat(withoutRows).isEqualTo(1 + 2); // the ineligible student, and the other major's 2 students
            String all = sheetText(without);
            assertThat(all).contains("Không đủ điều kiện: Nợ học phí");
            assertThat(sheetText(groups)).contains("Leader", s.get(0).getStudentCode()).doesNotContain("Kotlin");
        }
    }

    private static String cell(Row row) {
        return new DataFormatter().formatCellValue(row.getCell(0));
    }

    /** Rows after the "Học kỳ" column header row. */
    private static int dataRows(Sheet sheet) {
        DataFormatter f = new DataFormatter();
        int header = -1;
        for (Row row : sheet) {
            if ("Học kỳ".equals(f.formatCellValue(row.getCell(0)))) {
                header = row.getRowNum();
            }
        }
        return sheet.getLastRowNum() - header;
    }

    private static String sheetText(Sheet sheet) {
        DataFormatter f = new DataFormatter();
        StringBuilder text = new StringBuilder();
        for (Row row : sheet) {
            row.forEach(c -> text.append(f.formatCellValue(c)).append('|'));
            text.append('\n');
        }
        return text.toString();
    }
}
