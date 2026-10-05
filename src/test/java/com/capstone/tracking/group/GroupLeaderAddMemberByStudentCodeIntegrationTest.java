package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.dto.AddMemberRequest;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GroupLeaderAddMemberByStudentCodeIntegrationTest extends WorkflowTestSupport {

    @Test
    @DisplayName("Case 1 & 8: Group Leader adds member directly by valid MSSV; internal UUID is preserved")
    void case1And8_validMssvAddsMemberAndPreservesInternalUuid() throws Exception {
        User admin = user("cs1-admin", Role.ADMIN);
        User leader = user("cs1-leader", Role.GROUP_LEADER);
        StudentGroup group = group("CS1-GRP", admin, false);
        join(group, leader, true);

        String mssv = "SE" + suffix.toUpperCase();
        User student = userRepository.save(User.builder()
                .email("student-" + suffix + "@gmail.com")
                .fullName("Nguyen Van A")
                .studentCode(mssv)
                .passwordHash("hash")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        String url = "/api/v1/groups/" + group.getId() + "/members";

        // Group Leader calls with studentCode
        postJson(url, leader, Map.of("studentCode", mssv, "isLeader", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(student.getId().toString()))
                .andExpect(jsonPath("$.userFullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.studentCode").value(mssv))
                .andExpect(jsonPath("$.isLeader").value(false));

        // Case 8: verify membership still stores user UUID internally in DB
        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(group.getId(), student.getId())
                .orElseThrow();
        assertThat(member.getUser().getId()).isEqualTo(student.getId());
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("Case 2: Non-existent MSSV returns 404 Not Found without 500")
    void case2_nonExistentMssvReturns404() throws Exception {
        User admin = user("cs2-admin", Role.ADMIN);
        User leader = user("cs2-leader", Role.GROUP_LEADER);
        StudentGroup group = group("CS2-GRP", admin, false);
        join(group, leader, true);

        String url = "/api/v1/groups/" + group.getId() + "/members";

        postJson(url, leader, Map.of("studentCode", "SE999999", "isLeader", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Case 3: Empty or blank input returns 400 Bad Request")
    void case3_emptyInputReturns400() throws Exception {
        User admin = user("cs3-admin", Role.ADMIN);
        User leader = user("cs3-leader", Role.GROUP_LEADER);
        StudentGroup group = group("CS3-GRP", admin, false);
        join(group, leader, true);

        String url = "/api/v1/groups/" + group.getId() + "/members";

        // Empty string
        postJson(url, leader, Map.of("studentCode", "", "isLeader", false))
                .andExpect(status().isBadRequest());

        // Whitespace only
        postJson(url, leader, Map.of("studentCode", "   ", "isLeader", false))
                .andExpect(status().isBadRequest());

        // Empty JSON object
        postJson(url, leader, Map.of("isLeader", false))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Case 4: Student already in group returns 409 Conflict")
    void case4_alreadyInGroupReturns409() throws Exception {
        User admin = user("cs4-admin", Role.ADMIN);
        User leader = user("cs4-leader", Role.GROUP_LEADER);
        StudentGroup group = group("CS4-GRP", admin, false);
        join(group, leader, true);

        String mssv = "SE" + suffix.toUpperCase();
        userRepository.save(User.builder()
                .email("student-cs4-" + suffix + "@gmail.com")
                .fullName("Student Four")
                .studentCode(mssv)
                .passwordHash("hash")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        String url = "/api/v1/groups/" + group.getId() + "/members";

        // First add: success
        postJson(url, leader, Map.of("studentCode", mssv))
                .andExpect(status().isCreated());

        // Duplicate add: 409 Conflict
        postJson(url, leader, Map.of("studentCode", mssv))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("Case 5: Group at full capacity (5 members) rejects new member with 409 Conflict")
    void case5_fullGroupRejectsNewMember() throws Exception {
        User admin = user("cs5-admin", Role.ADMIN);
        User leader = user("cs5-leader", Role.GROUP_LEADER);
        StudentGroup group = group("CS5-GRP", admin, false);
        join(group, leader, true);

        String url = "/api/v1/groups/" + group.getId() + "/members";

        // Add 4 more students to reach capacity of 5
        for (int i = 1; i <= 4; i++) {
            String code = "SE5" + i + suffix.substring(0, 3).toUpperCase();
            userRepository.save(User.builder()
                    .email("cs5-s" + i + "-" + suffix + "@fpt.edu.vn")
                    .fullName("Member " + i)
                    .studentCode(code)
                    .passwordHash("x")
                    .role(Role.STUDENT)
                    .status(UserStatus.ACTIVE)
                    .eligible(true)
                    .build());
            postJson(url, leader, Map.of("studentCode", code)).andExpect(status().isCreated());
        }

        // Try adding 6th student
        String sixthCode = "SE59" + suffix.substring(0, 3).toUpperCase();
        userRepository.save(User.builder()
                .email("cs5-s6-" + suffix + "@fpt.edu.vn")
                .fullName("Member 6")
                .studentCode(sixthCode)
                .passwordHash("x")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        postJson(url, leader, Map.of("studentCode", sixthCode))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("Case 6: Leader of another group cannot add member (403 Forbidden)")
    void case6_leaderOfAnotherGroupForbidden() throws Exception {
        User admin = user("cs6-admin", Role.ADMIN);
        User leaderA = user("cs6-leader-a", Role.GROUP_LEADER);
        User leaderB = user("cs6-leader-b", Role.GROUP_LEADER);
        StudentGroup groupA = group("CS6-GRPA", admin, false);
        StudentGroup groupB = group("CS6-GRPB", admin, false);
        join(groupA, leaderA, true);
        join(groupB, leaderB, true);

        String mssv = "SE" + suffix.toUpperCase();
        userRepository.save(User.builder()
                .email("student-cs6-" + suffix + "@gmail.com")
                .fullName("Student Six")
                .studentCode(mssv)
                .passwordHash("hash")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        // Leader B tries to add member to Group A -> 403 Forbidden
        postJson("/api/v1/groups/" + groupA.getId() + "/members", leaderB, Map.of("studentCode", mssv))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Case 7: Add -> Remove -> Re-add same MSSV succeeds without duplicate rows")
    void case7_addRemoveReAddSucceeds() throws Exception {
        User admin = user("cs7-admin", Role.ADMIN);
        User leader = user("cs7-leader", Role.GROUP_LEADER);
        StudentGroup group = group("CS7-GRP", admin, false);
        join(group, leader, true);

        String mssv = "SE" + suffix.toUpperCase();
        User student = userRepository.save(User.builder()
                .email("student-cs7-" + suffix + "@gmail.com")
                .fullName("Student Seven")
                .studentCode(mssv)
                .passwordHash("hash")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        String url = "/api/v1/groups/" + group.getId() + "/members";

        // 1. Add by MSSV
        String memberId = body(postJson(url, leader, Map.of("studentCode", mssv))
                .andExpect(status().isCreated()))
                .get("id").asText();

        // 2. Remove member
        mockMvc.perform(delete(url + "/" + memberId).header("Authorization", bearer(leader)))
                .andExpect(status().isNoContent());

        // Verify status is REMOVED
        assertThat(groupMemberRepository.findById(UUID.fromString(memberId)).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.REMOVED);

        // 3. Re-add same MSSV
        postJson(url, leader, Map.of("studentCode", mssv))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(memberId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Only 2 active members (leader + student), no duplicate active records
        assertThat(groupMemberRepository.findByGroupIdAndStatus(group.getId(), MemberStatus.ACTIVE))
                .hasSize(2);
    }
}
