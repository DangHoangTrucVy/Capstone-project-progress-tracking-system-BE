package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verification of the complete Group Leader Application Approval flow:
 * Student applies -> Leader approves -> Application status is APPROVED ->
 * Active membership is created/reactivated -> Member appears in member list.
 */
class LeaderApproveApplicationIntegrationTest extends WorkflowTestSupport {

    @Autowired
    private GroupJoinRequestRepository joinRequests;

    @Autowired
    private GroupJoinService groupJoinService;

    private UUID createGroup(User leader) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", "Fall2026"))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID apply(User student, UUID groupId) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups/" + groupId + "/applications", student, Map.of())
                .andExpect(status().isCreated())).get("id").asText());
    }

    @Test
    @DisplayName("Case 1: Student apply -> Leader approve -> Application APPROVED -> active membership exists")
    void case1_studentApply_leaderApprove_enrolledDirectly() throws Exception {
        User leader = user("c1-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);

        User student = user("c1-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);

        // Leader approves application
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId.toString()))
                .andExpect(jsonPath("$.type").value("APPLY"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.student.userId").value(student.getId().toString()));

        // Application status in database is APPROVED
        GroupJoinRequest savedApp = joinRequests.findById(appId).orElseThrow();
        assertThat(savedApp.getStatus()).isEqualTo(JoinRequestStatus.APPROVED);
        assertThat(savedApp.getRespondedAt()).isNotNull();

        // Active membership exists in database
        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId()).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.isLeader()).isFalse();
        assertThat(member.getJoinedAt()).isNotNull();
    }

    @Test
    @DisplayName("Case 2: GET members after approve -> newly approved student appears in response")
    void case2_getMembersAfterApprove_studentAppearsInRoster() throws Exception {
        User leader = user("c2-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);

        User student = userRepository.save(User.builder()
                .email("c2-stu-" + suffix + "@fpt.edu.vn")
                .fullName("Tran Van B")
                .studentCode("SE170001")
                .passwordHash("hash")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        UUID appId = apply(student, groupId);
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isOk());

        // 1. GET /api/v1/groups/{id}/members endpoint
        getAs("/api/v1/groups/" + groupId + "/members", leader)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.userId == '" + student.getId() + "')].userFullName").value("Tran Van B"))
                .andExpect(jsonPath("$[?(@.userId == '" + student.getId() + "')].studentCode").value("SE170001"))
                .andExpect(jsonPath("$[?(@.userId == '" + student.getId() + "')].status").value("ACTIVE"))
                .andExpect(jsonPath("$[?(@.userId == '" + student.getId() + "')].isLeader").value(false));

        // 2. GET /api/v1/groups/{id} endpoint (used by FE MemberGroup.jsx getGroupById)
        getAs("/api/v1/groups/" + groupId, leader)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[?(@.userId == '" + student.getId() + "')].userFullName").value("Tran Van B"))
                .andExpect(jsonPath("$.members[?(@.userId == '" + student.getId() + "')].studentCode").value("SE170001"));
    }

    @Test
    @DisplayName("Case 3: Group already full (5 members) -> approve fails with 409 Conflict -> application stays PENDING")
    void case3_groupAlreadyFull_approveFails_applicationStaysPending() throws Exception {
        User leader = user("c3-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);

        // Add 3 more members (total 4 members, so group still has 1 slot open)
        for (int i = 1; i <= 3; i++) {
            User m = user("c3-m" + i, Role.STUDENT);
            join(studentGroupRepository.findById(groupId).orElseThrow(), m, false);
        }
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(4);

        // Applicant applies while group is not full yet
        User applicant = user("c3-app", Role.STUDENT);
        UUID appId = apply(applicant, groupId);

        // Now a 5th member joins, making the group full (5 members)
        User fifth = user("c3-m5", Role.STUDENT);
        join(studentGroupRepository.findById(groupId).orElseThrow(), fifth, false);
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(5);

        // Approve should fail with 409 Conflict
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        // Application must remain PENDING
        GroupJoinRequest app = joinRequests.findById(appId).orElseThrow();
        assertThat(app.getStatus()).isEqualTo(JoinRequestStatus.PENDING);

        // No membership record created for applicant
        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupId, applicant.getId())).isEmpty();
    }

    @Test
    @DisplayName("Case 4: Leader of Group A approves application of Group B -> 403 Forbidden")
    void case4_leaderGroupA_approvesGroupB_returns403() throws Exception {
        User leaderA = user("c4-ldra", Role.STUDENT);
        UUID groupA = createGroup(leaderA);

        User leaderB = user("c4-ldrb", Role.STUDENT);
        UUID groupB = createGroup(leaderB);

        User applicant = user("c4-app", Role.STUDENT);
        UUID appToB = apply(applicant, groupB);

        // Leader A attempts to approve application to Group B
        postJson("/api/v1/applications/" + appToB + "/approve", leaderA, Map.of())
                .andExpect(status().isForbidden());

        // Application to Group B remains PENDING
        GroupJoinRequest app = joinRequests.findById(appToB).orElseThrow();
        assertThat(app.getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupB, applicant.getId())).isEmpty();
    }

    @Test
    @DisplayName("Case 5: Student already active member -> approve fails cleanly with 409 Conflict")
    void case5_studentAlreadyActiveMember_approveFailsCleanly() throws Exception {
        User leader = user("c5-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);

        User student = user("c5-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);

        // Approve once -> success
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isOk());

        // Attempting to approve again (or if student is already active) returns 409 Conflict
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isConflict());

        // Active members in group remains 2 (no duplicate rows)
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(2);
    }

    @Test
    @DisplayName("Case 6: Student has inactive membership row (previously removed) -> approve reactivates to ACTIVE")
    void case6_studentWithInactiveMembership_approveReactivatesWithoutDuplicate() throws Exception {
        User leader = user("c6-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);

        User student = user("c6-stu", Role.STUDENT);
        StudentGroup group = studentGroupRepository.findById(groupId).orElseThrow();

        // 1. Join and then get removed
        join(group, student, false);
        GroupMember initialMember = groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId()).orElseThrow();
        initialMember.setStatus(MemberStatus.REMOVED);
        groupMemberRepository.save(initialMember);

        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId())).isPresent();
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(1);

        // 2. Student applies again
        UUID appId = apply(student, groupId);

        // 3. Leader approves
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isOk());

        // 4. Verify existing row is reactivated, no duplicate row created
        GroupMember reactivated = groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId()).orElseThrow();
        assertThat(reactivated.getId()).isEqualTo(initialMember.getId());
        assertThat(reactivated.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(2);
    }

    @Test
    @DisplayName("Case 7: Transaction consistency -> if enrollment validation fails, application is not left APPROVED")
    void case7_transactionConsistency_rollbackIfEnrollmentFails() throws Exception {
        User leader = user("c7-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);

        User student = user("c7-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);

        // Ineligible or disabled student status before approval
        student.setStatus(UserStatus.SUSPENDED);
        userRepository.save(student);

        // Leader attempts to approve
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isBadRequest());

        // Application status must NOT be APPROVED
        GroupJoinRequest app = joinRequests.findById(appId).orElseThrow();
        assertThat(app.getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId())).isEmpty();
    }
}
