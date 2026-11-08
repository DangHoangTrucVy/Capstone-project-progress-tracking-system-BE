package com.capstone.tracking.semester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.major.Major;
import com.capstone.tracking.roster.Eligibility;
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

/** Semesters (3.1), Moderator (3.8), rules (3.2), phases (3.5, 3.6), imports (4.1, 4.2) and eligibility files (5). */
class SemesterAndImportIntegrationTest extends TestSupport {

    @Test
    void theAdminCreatesASemesterWithoutStatusAndAssignsModerator() throws Exception {
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
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.phase").value("OPENING"))
                .andExpect(jsonPath("$.rules.maxGroupSize").value(5))
                .andExpect(jsonPath("$.rules.defaultMinRoomSize").value(3))
                .andExpect(jsonPath("$.studentCount").value(0));
        postJson("/api/v1/semesters", admin, body).andExpect(status().isConflict());

        // A deadline closer than the configured 10 minutes is refused.
        putJson("/api/v1/semesters/" + code + "/deadline", admin, Map.of("deadline", Instant.now().plus(Duration.ofMinutes(5)).toString()))
                .andExpect(status().isBadRequest());

        // The Moderator is a lecturer from the imported list; then they edit the timeline, not the dates.
        User student = student("sem-student");
        putJson("/api/v1/semesters/" + code + "/moderator", admin, Map.of("lecturerId", student.getId().toString()))
                .andExpect(status().isBadRequest());
        putJson("/api/v1/semesters/" + code + "/moderator", admin, Map.of("lecturerId", lecturer.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.moderator.id").value(lecturer.getId().toString()));
        putJson("/api/v1/semesters/" + code + "/deadline", lecturer, Map.of("deadline", Instant.now().plus(Duration.ofDays(9)).toString()))
                .andExpect(status().isOk());
        putJson("/api/v1/semesters/" + code + "/rules", lecturer, Map.of("maxGroupSize", 6, "defaultMinRoomSize", 4))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxGroupSize").value(6));
        putJson("/api/v1/semesters/" + code + "/rules", lecturer, Map.of("defaultMinRoomSize", 7))
                .andExpect(status().isBadRequest());
        putJson("/api/v1/semesters/" + code, lecturer, Map.of("name", "Renamed")).andExpect(status().isForbidden());

        // Empty, so it may be deleted.
        deleteAs("/api/v1/semesters/" + code, admin).andExpect(status().isNoContent());
    }

    @Test
    void importCreatesOneScopePerMajorAndFormationStartsOnlyWithStudentsDeadlineAndRooms() throws Exception {
        World w = openingWorld(3, 2);
        assertThat(enrollmentRepository.countByScopeId(w.scope().getId())).isEqualTo(3);
        getAs("/api/v1/semesters/" + w.code(), w.admin()).andExpect(jsonPath("$.studentCount").value(5))
                .andExpect(jsonPath("$.majorCount").value(2)).andExpect(jsonPath("$.phase").value("OPENING"));
        // Students only view in OPENING.
        postJson("/api/v1/rooms/" + w.rooms().get(0) + "/join", w.students().get(0), Map.of())
                .andExpect(status().isConflict());
        // A major with students but no room blocks the start.
        Major third = major();
        enrol(w.semester(), third, 1);
        JsonNode check = body(getAs("/api/v1/semesters/" + w.code() + "/phase-check", w.moderator()).andExpect(status().isOk()));
        assertThat(check.get("canStartForming").asBoolean()).isFalse();
        assertThat(check.get("majorsWithoutRooms").size()).isEqualTo(1);
        postJson("/api/v1/semesters/" + w.code() + "/start-formation", w.moderator(), Map.of())
                .andExpect(status().isConflict());
        // The room plan suggests ceil(1 / 3) = 1 room; creating without a count creates the missing ones.
        postJson("/api/v1/semesters/" + w.code() + "/rooms", w.moderator(), Map.of("majorId", third.getId()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$[0].name").value("Room 01"));
        // Another lecturer is not this semester's Moderator.
        User other = user("not-moderator", Role.LECTURER);
        postJson("/api/v1/semesters/" + w.code() + "/start-formation", other, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/semesters/" + w.code() + "/start-formation", w.moderator(), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FORMING"));
        // Once formation started, the maximum size only grows.
        putJson("/api/v1/semesters/" + w.code() + "/rules", w.moderator(), Map.of("maxGroupSize", 4))
                .andExpect(status().isConflict());
        // Old subject endpoints are gone.
        getAs("/api/v1/semesters/" + w.code() + "/subjects", w.moderator()).andExpect(status().isNotFound());
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
        getAs("/api/v1/semesters/" + code + "/imports", admin).andExpect(status().isNotFound());

        // A missing required column fails the whole file.
        upload(url, admin, "bad.csv", "MSSV,Họ tên,Email\nX,Y,z@fpt.edu.vn\n").andExpect(status().isBadRequest());
    }

    @Test
    void onceFormationStartedOnlyContactDetailsMayChange() throws Exception {
        World w = world(1, 0);
        User s = w.students().get(0);
        String header = "MSSV,Họ tên,Email,Số điện thoại,Kỳ,Ngành\n";
        String url = "/api/v1/semesters/" + w.code() + "/imports/students";
        JsonNode report = body(upload(url + "/preview", w.admin(), "f.csv", header
                + s.getStudentCode() + ",Tên Mới," + s.getEmail() + ",0900000000,8," + w.otherMajor().getCode() + "\n"
                + "NEW" + suffix + ",Người Mới,new" + suffix + "@fpt.edu.vn,,8," + w.major().getCode() + "\n"));
        assertThat(report.get("errorRows").asInt()).isEqualTo(2);
        assertThat(report.get("errors").toString()).contains("Không đổi ngành", "không thêm sinh viên mới");
        upload(url, w.admin(), "f.csv", header + s.getStudentCode() + ",Tên Mới," + s.getEmail() + ",0900000000,8,"
                + w.major().getCode() + "\n").andExpect(status().isOk());
        assertThat(userRepository.findById(s.getId()).orElseThrow().getFullName()).isEqualTo("Tên Mới");
    }

    @Test
    void theIneligibilityFileFlagsStudentsAndTakesThemOutOfTheirRoom() throws Exception {
        World w = world(2, 0);
        User flagged = w.students().get(0);
        UUID room = createGroup(w, flagged, "Flagged room");
        String csv = "MSSV,Lý do\n" + flagged.getStudentCode() + ",Nợ môn\nNOPE" + suffix + ",x\n";
        String url = "/api/v1/semesters/" + w.code() + "/imports/eligibility";
        JsonNode preview = body(upload(url + "/preview", w.admin(), "e.csv", csv).andExpect(status().isOk()));
        assertThat(preview.get("updatedRows").asInt()).isEqualTo(1);
        assertThat(preview.get("errorRows").asInt()).isEqualTo(1);
        upload(url, w.admin(), "e.csv", csv).andExpect(status().isOk());
        assertThat(enrollmentRepository.findBySemesterAndUser(w.code(), flagged.getId()).orElseThrow()
                .getEligibility()).isEqualTo(Eligibility.INELIGIBLE);
        // Out of the room; the room went back to empty and is kept.
        assertThat(memberRepository.countActive(room)).isZero();
        assertThat(groupRepository.findById(room).orElseThrow().isDissolved()).isFalse();
        // The Moderator does not import files; only the Admin does.
        upload(url, w.moderator(), "e.csv", csv).andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesModeratorAndCannotReuseAStudentEmail() throws Exception {
        User admin = user("account-admin", Role.ADMIN);
        User student = student("account-student");
        postJson("/api/v1/users/moderators", admin, Map.of("email", "moderator-" + suffix + "@gmail.com",
                "fullName", "Moderator", "password", "Password123")).andExpect(status().isCreated());
        postJson("/api/v1/users/moderators", admin, Map.of("email", student.getEmail(),
                "fullName", "Duplicate", "password", "Password123")).andExpect(status().isConflict());
    }

}
