package com.capstone.tracking.semester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Semesters (3.1), Moderator (3.6), subjects and scopes (3.2), imports (4.1, 4.2) and eligibility files (5). */
class SemesterAndImportIntegrationTest extends TestSupport {

    @Test
    void theAdminCreatesASemesterAndItsStatusFollowsTheDates() throws Exception {
        User admin = user("sem-admin", Role.ADMIN);
        User lecturer = user("sem-lecturer", Role.LECTURER);
        String code = freeSemesterCode();
        Map<String, Object> body = new HashMap<>();
        body.put("code", "x" + code);
        body.put("startDate", LocalDate.now().toString());
        body.put("endDate", LocalDate.now().plusDays(100).toString());
        postJson("/api/v1/semesters", admin, body).andExpect(status().isBadRequest());
        body.put("code", code.toLowerCase());
        postJson("/api/v1/semesters", lecturer, body).andExpect(status().isForbidden());
        postJson("/api/v1/semesters", admin, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value("ONGOING"))
                .andExpect(jsonPath("$.studentCount").value(0));
        postJson("/api/v1/semesters", admin, body).andExpect(status().isConflict());

        // A deadline closer than 48 hours is refused (3.5).
        putJson("/api/v1/semesters/" + code, admin, Map.of("defaultDeadline", Instant.now().plus(Duration.ofHours(10)).toString()))
                .andExpect(status().isBadRequest());

        // The Moderator is a lecturer from the imported list; then they edit the timeline, not the dates.
        User student = student("sem-student");
        putJson("/api/v1/semesters/" + code + "/moderator", admin, Map.of("lecturerId", student.getId().toString()))
                .andExpect(status().isBadRequest());
        putJson("/api/v1/semesters/" + code + "/moderator", admin, Map.of("lecturerId", lecturer.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.moderator.id").value(lecturer.getId().toString()));
        putJson("/api/v1/semesters/" + code, lecturer, Map.of("defaultDeadline", Instant.now().plus(Duration.ofDays(9)).toString()))
                .andExpect(status().isOk());
        putJson("/api/v1/semesters/" + code, lecturer, Map.of("name", "Renamed")).andExpect(status().isForbidden());

        postJson("/api/v1/semesters/" + code + "/archive", admin, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        String list = body(getAs("/api/v1/semesters", admin)).toString();
        assertThat(list).doesNotContain("\"code\":\"" + code + "\"");
        assertThat(body(getAs("/api/v1/semesters?status=ARCHIVED", admin)).toString()).contains("\"code\":\"" + code + "\"");
        // Empty, so it may be deleted.
        deleteAs("/api/v1/semesters/" + code, admin).andExpect(status().isNoContent());
    }

    @Test
    void aSubjectCreatesOneScopePerMajorOfItsTermAndOpensOnTheCommonDate() throws Exception {
        World w = world(3, 2);
        JsonNode subject = body(getAs("/api/v1/subjects/" + w.subjectId(), w.moderator()).andExpect(status().isOk()));
        assertThat(subject.get("scopes").size()).isEqualTo(2);
        assertThat(w.scope().getPhase()).isEqualTo(ScopePhase.OPEN);
        assertThat(enrollmentRepository.countByScopeId(w.scope().getId())).isEqualTo(3);
        getAs("/api/v1/semesters/" + w.code(), w.admin()).andExpect(jsonPath("$.studentCount").value(5))
                .andExpect(jsonPath("$.subjectCount").value(1));

        // Another lecturer is not this semester's Moderator.
        User other = user("not-moderator", Role.LECTURER);
        postJson("/api/v1/semesters/" + w.code() + "/subjects", other,
                Map.of("code", "EXE", "name", "Khởi nghiệp", "targetTerm", 7)).andExpect(status().isForbidden());
        // Students only see subjects of their term: a term-7 subject has no scope here.
        String exe = body(postJson("/api/v1/semesters/" + w.code() + "/subjects", w.moderator(),
                Map.of("code", "EXE", "name", "Khởi nghiệp", "targetTerm", 7)).andExpect(status().isCreated()))
                .get("id").asText();
        getAs("/api/v1/subjects/" + exe, w.moderator()).andExpect(jsonPath("$.scopes.length()").value(0));
        deleteAs("/api/v1/subjects/" + exe, w.moderator()).andExpect(status().isNoContent());
        // A subject whose scopes opened cannot be deleted.
        deleteAs("/api/v1/subjects/" + w.subjectId(), w.moderator()).andExpect(status().isConflict());
    }

    @Test
    void aScopeWithoutModeratorOrDeadlineDoesNotOpen() throws Exception {
        World w = world(1, 0);
        // A second subject in a semester whose deadline already passed stays PREPARING.
        Semester s = semesterRepository.findById(w.code()).orElseThrow();
        s.setDefaultDeadline(Instant.now().minusSeconds(60));
        semesterRepository.save(s);
        String id = body(postJson("/api/v1/semesters/" + w.code() + "/subjects", w.moderator(),
                Map.of("code", "CAP2", "name", "Capstone 2", "targetTerm", 8)).andExpect(status().isCreated())).get("id").asText();
        UUID scopeId = scopeRepository.findBySubjectIdAndMajorId(UUID.fromString(id), w.major().getId()).orElseThrow().getId();
        scopeService.tick(scopeId);
        assertThat(scopeRepository.findById(scopeId).orElseThrow().getPhase()).isEqualTo(ScopePhase.PREPARING);
        assertThat(scopeRepository.findById(scopeId).orElseThrow().getOpenBlockedNotifiedAt()).isNotNull();
        // A new own deadline lets it open at the next tick.
        putJson("/api/v1/scopes/" + scopeId + "/deadline", w.moderator(),
                Map.of("deadline", Instant.now().plus(Duration.ofDays(5)).toString())).andExpect(status().isOk());
        scopeService.tick(scopeId);
        assertThat(scopeRepository.findById(scopeId).orElseThrow().getPhase()).isEqualTo(ScopePhase.OPEN);
    }

    @Test
    void theStudentListIsCheckedBeforeItIsWrittenAndReimportingNeverDuplicates() throws Exception {
        User admin = user("imp-admin", Role.ADMIN);
        User lecturer = user("imp-lecturer", Role.LECTURER);
        String code = freeSemesterCode();
        postJson("/api/v1/semesters", admin, Map.of("code", code, "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(90).toString())).andExpect(status().isCreated());
        String a = "IA" + suffix.toUpperCase();
        String b = "IB" + suffix.toUpperCase();
        String csv = "MSSV,Họ tên,Email,Số điện thoại,Kỳ,Ngành\n"
                + a + ",Nguyễn Văn A," + a.toLowerCase() + "@fpt.edu.vn,0901234567,8,Ngành " + suffix + "\n"
                + b + ",Trần Thị B," + b.toLowerCase() + "@fpt.edu.vn,0907654321,8,Ngành " + suffix + "\n"
                + "DUP" + suffix + ",Dup 1,dup1" + suffix + "@fpt.edu.vn,,8,SE\n"
                + "DUP" + suffix + ",Dup 2,dup2" + suffix + "@fpt.edu.vn,,8,SE\n"
                + "NT" + suffix + ",No Term,nt" + suffix + "@fpt.edu.vn,,,SE\n"
                + "LE" + suffix + ",Lecturer Email," + lecturer.getEmail() + ",,8,SE\n";
        String url = "/api/v1/semesters/" + code + "/imports/students";

        JsonNode preview = body(upload(url + "/preview", admin, "list.csv", csv).andExpect(status().isOk()));
        assertThat(preview.get("committed").asBoolean()).isFalse();
        assertThat(preview.get("newRows").asInt()).isEqualTo(2);
        assertThat(preview.get("errorRows").asInt()).isEqualTo(4);
        assertThat(preview.get("errors").toString()).contains("Trùng mã số trong file", "Thiếu kỳ học",
                "Email đã thuộc tài khoản giảng viên");
        assertThat(preview.get("newMajors").toString()).contains("Ngành " + suffix);
        assertThat(userRepository.findByStudentCodeIgnoreCase(a)).isEmpty();

        upload(url, admin, "list.csv", csv).andExpect(status().isOk()).andExpect(jsonPath("$.committed").value(true));
        assertThat(semesterStudentRepository.countBySemesterCode(code)).isEqualTo(2);
        // Imported again with a new phone: matched by student code + semester, updated, not duplicated.
        JsonNode again = body(upload(url, admin, "list2.csv", csv.replace("0901234567", "0911111111")).andExpect(status().isOk()));
        assertThat(again.get("newRows").asInt()).isZero();
        assertThat(again.get("updatedRows").asInt()).isEqualTo(1);
        assertThat(semesterStudentRepository.countBySemesterCode(code)).isEqualTo(2);
        assertThat(userRepository.findByStudentCodeIgnoreCase(a).orElseThrow().getPhone()).isEqualTo("0911111111");
        getAs("/api/v1/semesters/" + code + "/imports", admin).andExpect(jsonPath("$.length()").value(2));

        // A missing required column fails the whole file.
        upload(url, admin, "bad.csv", "MSSV,Họ tên,Email\nX,Y,z@fpt.edu.vn\n").andExpect(status().isBadRequest());
    }

    @Test
    void onceAScopeOpenedOnlyContactDetailsMayChange() throws Exception {
        World w = world(1, 0);
        User s = w.students().get(0);
        String header = "MSSV,Họ tên,Email,Số điện thoại,Kỳ,Ngành\n";
        String url = "/api/v1/semesters/" + w.code() + "/imports/students";
        JsonNode report = body(upload(url + "/preview", w.admin(), "f.csv", header
                + s.getStudentCode() + ",Tên Mới," + s.getEmail() + ",0900000000,8," + w.otherMajor().getCode() + "\n"
                + "NEW" + suffix + ",Người Mới,new" + suffix + "@fpt.edu.vn,,8," + w.major().getCode() + "\n"));
        assertThat(report.get("errorRows").asInt()).isEqualTo(2);
        assertThat(report.get("errors").toString()).contains("không đổi ngành hoặc kỳ học", "không thêm sinh viên mới");
        upload(url, w.admin(), "f.csv", header + s.getStudentCode() + ",Tên Mới," + s.getEmail() + ",0900000000,8,"
                + w.major().getCode() + "\n").andExpect(status().isOk());
        assertThat(userRepository.findById(s.getId()).orElseThrow().getFullName()).isEqualTo("Tên Mới");
    }

    @Test
    void theIneligibilityFileFlagsStudentsPerSubject() throws Exception {
        World w = world(2, 0);
        User flagged = w.students().get(0);
        String csv = "MSSV,Môn,Lý do\n" + flagged.getStudentCode() + ",CAP,Nợ môn\nNOPE" + suffix + ",,x\n";
        String url = "/api/v1/semesters/" + w.code() + "/imports/eligibility";
        JsonNode preview = body(upload(url + "/preview", w.admin(), "e.csv", csv).andExpect(status().isOk()));
        assertThat(preview.get("updatedRows").asInt()).isEqualTo(1);
        assertThat(preview.get("errorRows").asInt()).isEqualTo(1);
        upload(url, w.admin(), "e.csv", csv).andExpect(status().isOk());
        assertThat(enrollmentRepository.findBySubjectIdAndUserId(w.subjectId(), flagged.getId()).orElseThrow()
                .getEligibility()).isEqualTo(Eligibility.INELIGIBLE);
        // The Moderator does not import files; only the Admin does.
        upload(url, w.moderator(), "e.csv", csv).andExpect(status().isForbidden());
    }

    @Test
    void lecturersAreImportedAndCannotShareAStudentsEmail() throws Exception {
        User admin = user("lect-admin", Role.ADMIN);
        User student = student("lect-student");
        String csv = "Họ tên,Email\nGiảng Viên Một,gv1-" + suffix + "@fpt.edu.vn\nTrùng,"
                + student.getEmail() + "\n";
        JsonNode report = body(upload("/api/v1/imports/lecturers", admin, "gv.csv", csv).andExpect(status().isOk()));
        assertThat(report.get("newRows").asInt()).isEqualTo(1);
        assertThat(report.get("errors").toString()).contains("Email đã thuộc tài khoản sinh viên");
        assertThat(userRepository.findByEmailIgnoreCase("gv1-" + suffix + "@fpt.edu.vn").orElseThrow().getRole())
                .isEqualTo(Role.LECTURER);
    }
}
