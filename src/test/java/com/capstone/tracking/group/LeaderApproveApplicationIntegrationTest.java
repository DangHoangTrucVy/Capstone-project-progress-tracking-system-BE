package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * YC10: Student applies -> Leader approves, which sends the applicant an Invite -> the applicant becomes an official
 * member only when they Accept it (YC11). Approving never enrolls anyone by itself.
 */
class LeaderApproveApplicationIntegrationTest extends WorkflowTestSupport {

    @Autowired
    private GroupJoinRequestRepository joinRequests;

    private UUID createGroup(User leader) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", "Fall2026"))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID apply(User student, UUID groupId) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups/" + groupId + "/applications", student, Map.of())
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID approve(User leader, UUID appId) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isOk())).get("id").asText());
    }

    private void accept(User student, UUID inviteId) throws Exception {
        postJson("/api/v1/invites/" + inviteId + "/accept", student, Map.of()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Case 1: approve -> Apply APPROVED and a PENDING Invite linked to it; the student is not a member yet")
    void case1_approveSendsInviteWithoutEnrolling() throws Exception {
        User leader = user("c1-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        User student = user("c1-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);

        String inviteId = body(postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("INVITE"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sourceApplicationId").value(appId.toString()))
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.student.userId").value(student.getId().toString()))).get("id").asText();

        GroupJoinRequest app = joinRequests.findById(appId).orElseThrow();
        assertThat(app.getStatus()).isEqualTo(JoinRequestStatus.APPROVED);
        assertThat(app.getRespondedAt()).isNotNull();
        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId())).isEmpty();
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(1);
        getAs("/api/v1/me/invites", student).andExpect(jsonPath("$[0].id").value(inviteId));
    }

    @Test
    @DisplayName("Case 2: after Accept the student appears in the member list and in the group")
    void case2_acceptAfterApprove_studentAppearsInRoster() throws Exception {
        User leader = user("c2-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        User student = userRepository.save(User.builder()
                .email("c2-stu-" + suffix + "@fpt.edu.vn")
                .fullName("Tran Van B")
                .studentCode("SE17" + suffix.replaceAll("[^0-9]", "1"))
                .passwordHash("hash")
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .eligible(true)
                .build());

        UUID inviteId = approve(leader, apply(student, groupId));
        getAs("/api/v1/groups/" + groupId + "/members", leader).andExpect(jsonPath("$", hasSize(1)));

        accept(student, inviteId);
        getAs("/api/v1/groups/" + groupId + "/members", leader)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.userId == '" + student.getId() + "')].userFullName").value("Tran Van B"))
                .andExpect(jsonPath("$[?(@.userId == '" + student.getId() + "')].isLeader").value(false));
        getAs("/api/v1/groups/" + groupId, leader)
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.members", hasSize(2)));
    }

    @Test
    @DisplayName("Case 3: group full (5) -> approve 409 and the Apply stays PENDING; a full group cannot be joined at Accept")
    void case3_groupFull() throws Exception {
        User leader = user("c3-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        for (int i = 1; i <= 3; i++) {
            join(studentGroupRepository.findById(groupId).orElseThrow(), user("c3-m" + i, Role.STUDENT), false);
        }
        User early = user("c3-early", Role.STUDENT);
        UUID earlyInvite = approve(leader, apply(early, groupId));
        User applicant = user("c3-app", Role.STUDENT);
        UUID appId = apply(applicant, groupId);

        // The Invite did not hold a seat (YC15): a 5th member fills the group before anyone accepts.
        join(studentGroupRepository.findById(groupId).orElseThrow(), user("c3-m5", Role.STUDENT), false);
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(5);

        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
        assertThat(joinRequests.findById(appId).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.PENDING);

        postJson("/api/v1/invites/" + earlyInvite + "/accept", early, Map.of()).andExpect(status().isConflict());
        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupId, early.getId())).isEmpty();
    }

    @Test
    @DisplayName("Case 4: the Leader of group A cannot approve an Apply to group B")
    void case4_leaderGroupA_approvesGroupB_returns403() throws Exception {
        User leaderA = user("c4-ldra", Role.STUDENT);
        createGroup(leaderA);
        User leaderB = user("c4-ldrb", Role.STUDENT);
        UUID groupB = createGroup(leaderB);
        User applicant = user("c4-app", Role.STUDENT);
        UUID appToB = apply(applicant, groupB);

        postJson("/api/v1/applications/" + appToB + "/approve", leaderA, Map.of()).andExpect(status().isForbidden());
        assertThat(joinRequests.findById(appToB).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(joinRequests.findByStudentIdAndTypeOrderByCreatedAtDesc(applicant.getId(), JoinRequestType.INVITE))
                .isEmpty();
    }

    @Test
    @DisplayName("Case 5: approving twice -> 409, still one Invite and no membership")
    void case5_approveTwice() throws Exception {
        User leader = user("c5-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        User student = user("c5-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);

        approve(leader, appId);
        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of()).andExpect(status().isConflict());

        assertThat(joinRequests.findByStudentIdAndTypeOrderByCreatedAtDesc(student.getId(), JoinRequestType.INVITE))
                .hasSize(1);
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("Case 6: a student removed earlier who re-applies is reactivated (same row) when accepting (YC21)")
    void case6_reapplyAfterRemoval_acceptReactivatesRow() throws Exception {
        User leader = user("c6-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        User student = user("c6-stu", Role.STUDENT);
        join(studentGroupRepository.findById(groupId).orElseThrow(), student, false);
        GroupMember initial = groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId()).orElseThrow();
        initial.setStatus(MemberStatus.REMOVED);
        groupMemberRepository.save(initial);

        UUID inviteId = approve(leader, apply(student, groupId));
        assertThat(groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId()).orElseThrow().getStatus())
                .isEqualTo(MemberStatus.REMOVED);

        accept(student, inviteId);
        GroupMember reactivated = groupMemberRepository.findByGroupIdAndUserId(groupId, student.getId()).orElseThrow();
        assertThat(reactivated.getId()).isEqualTo(initial.getId());
        assertThat(reactivated.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)).isEqualTo(2);
    }

    @Test
    @DisplayName("Case 7: applicant no longer eligible -> approve 403 NOT_ELIGIBLE, Apply stays PENDING, no Invite")
    void case7_ineligibleApplicant() throws Exception {
        User leader = user("c7-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        User student = user("c7-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);

        student.setEligible(false);
        student.setIneligibleReason("Thiếu tín chỉ");
        userRepository.save(student);

        postJson("/api/v1/applications/" + appId + "/approve", leader, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_ELIGIBLE"));
        assertThat(joinRequests.findById(appId).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(joinRequests.findByStudentIdAndTypeOrderByCreatedAtDesc(student.getId(), JoinRequestType.INVITE))
                .isEmpty();
    }

    @Test
    @DisplayName("Case 8: the Leader had already invited the applicant -> approve reuses that Invite, no duplicate")
    void case8_existingInviteIsReused() throws Exception {
        User leader = user("c8-ldr", Role.STUDENT);
        UUID groupId = createGroup(leader);
        User student = user("c8-stu", Role.STUDENT);
        UUID appId = apply(student, groupId);
        String direct = body(postJson("/api/v1/groups/" + groupId + "/invites", reload(leader),
                Map.of("userId", student.getId())).andExpect(status().isCreated())).get("id").asText();

        postJson("/api/v1/applications/" + appId + "/approve", reload(leader), Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(direct))
                .andExpect(jsonPath("$.sourceApplicationId").value(appId.toString()));
        assertThat(joinRequests.findByStudentIdAndTypeOrderByCreatedAtDesc(student.getId(), JoinRequestType.INVITE))
                .hasSize(1);
    }

    private User reload(User u) {
        return userRepository.findById(u.getId()).orElseThrow();
    }
}
