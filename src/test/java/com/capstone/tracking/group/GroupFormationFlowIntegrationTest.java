package com.capstone.tracking.group;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end group formation per YC06-YC22: Apply / Invite / Accept, limits, expiry, privacy, leaving, kicking. */
class GroupFormationFlowIntegrationTest extends WorkflowTestSupport {

    @Autowired private GroupJoinRequestRepository joinRequests;
    @Autowired private GroupJoinService joinService;

    private UUID createGroup(User leader) throws Exception {
        return UUID.fromString(body(postJson("/api/v1/groups", leader, Map.of("semester", "S-" + suffix))
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

    /** Brings {@code extraMembers} more students into the group through direct Invite + Accept. */
    private UUID fill(User leader, UUID group, int extraMembers) throws Exception {
        for (int i = 0; i < extraMembers; i++) {
            User s = user("m" + i, Role.STUDENT);
            accept(s, invite(leader, group, s));
        }
        return group;
    }

    @Test
    void applyApproveAcceptJoinsOnlyAtAccept() throws Exception {
        User leader = user("leader", Role.STUDENT);
        User student = user("applicant", Role.STUDENT);
        UUID group = createGroup(leader);

        UUID app = apply(student, group);
        postJson("/api/v1/groups/" + group + "/applications", student, Map.of()).andExpect(status().isConflict());

        // The leader approves: the student immediately becomes an active group member
        postJson("/api/v1/applications/" + app + "/approve", leader, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("APPLY"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, student.getId(), MemberStatus.ACTIVE)).isTrue();
        getAs("/api/v1/me/applications", student).andExpect(jsonPath("$[0].status").value("APPROVED"));
        getAs("/api/v1/groups/" + group, leader).andExpect(jsonPath("$.memberCount").value(2));

        // A member can no longer apply to another group (YC13).
        UUID other = createGroup(user("leader2", Role.STUDENT));
        postJson("/api/v1/groups/" + other + "/applications", student, Map.of()).andExpect(status().isConflict());
    }

    @Test
    void studentCanHaveOnlyThreeOpenApplicationsAndWithdrawFreesASlot() throws Exception {
        User student = user("busy", Role.STUDENT);
        UUID g1 = createGroup(user("l1", Role.STUDENT));
        UUID g2 = createGroup(user("l2", Role.STUDENT));
        UUID g3 = createGroup(user("l3", Role.STUDENT));
        UUID g4 = createGroup(user("l4", Role.STUDENT));
        UUID a1 = apply(student, g1);
        apply(student, g2);
        apply(student, g3);
        postJson("/api/v1/groups/" + g4 + "/applications", student, Map.of()).andExpect(status().isConflict());

        postJson("/api/v1/applications/" + a1 + "/withdraw", student, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        apply(student, g4);
        // Only the applicant can withdraw.
        UUID g5 = createGroup(user("l5", Role.STUDENT));
        UUID other = apply(user("other", Role.STUDENT), g5);
        postJson("/api/v1/applications/" + other + "/withdraw", student, Map.of()).andExpect(status().isForbidden());
    }

    @Test
    void expiredApplicationFreesTheSlotAndCannotBeApproved() throws Exception {
        User student = user("late", Role.STUDENT);
        User leader = user("late-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        UUID app = apply(student, group);
        GroupJoinRequest request = joinRequests.findById(app).orElseThrow();
        request.setExpiresAt(Instant.now().minusSeconds(5));
        joinRequests.save(request);

        postJson("/api/v1/applications/" + app + "/approve", leader, Map.of()).andExpect(status().isConflict());
        getAs("/api/v1/me/applications", student).andExpect(jsonPath("$[0].status").value("EXPIRED"));
        // The dead request neither blocks applying again nor counts toward the limit of 3.
        apply(student, group);
        joinService.expireDue();
        assertThat(joinRequests.findById(app).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.EXPIRED);
    }

    @Test
    void adminConfiguresRequestLifetimePerSemester() throws Exception {
        User admin = user("ttl-admin", Role.ADMIN);
        User leader = user("ttl-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        Instant before = Instant.now();
        UUID defaultApp = apply(user("ttl-a", Role.STUDENT), group);
        assertThat(joinRequests.findById(defaultApp).orElseThrow().getExpiresAt())
                .isBetween(before.plusSeconds(47 * 3600), Instant.now().plusSeconds(48 * 3600 + 5));

        mockMvc.perform(put("/api/v1/semesters/S-" + suffix + "/join-settings").header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"ttlHours\":6}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/semesters/S-" + suffix + "/join-settings").header("Authorization", bearer(leader))
                .contentType("application/json").content("{\"ttlHours\":6}")).andExpect(status().isForbidden());
        UUID shortApp = apply(user("ttl-b", Role.STUDENT), group);
        assertThat(joinRequests.findById(shortApp).orElseThrow().getExpiresAt())
                .isBefore(Instant.now().plusSeconds(6 * 3600 + 5));
    }

    @Test
    void acceptingCancelsEveryOtherOpenRequestOfTheStudent() throws Exception {
        User student = user("popular", Role.STUDENT);
        User l1 = user("pl1", Role.STUDENT);
        User l2 = user("pl2", Role.STUDENT);
        User l3 = user("pl3", Role.STUDENT);
        UUID g1 = createGroup(l1);
        UUID g2 = createGroup(l2);
        UUID g3 = createGroup(l3);
        UUID i1 = invite(l1, g1, student);
        UUID i2 = invite(l2, g2, student);
        // Invites do not count toward the Apply limit, and an Apply can coexist with Invites.
        UUID app = apply(student, g3);
        postJson("/api/v1/groups/" + g1 + "/invites", l1, Map.of("userId", student.getId())).andExpect(status().isConflict());

        accept(student, i1);
        assertThat(joinRequests.findById(i2).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        assertThat(joinRequests.findById(app).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        postJson("/api/v1/invites/" + i2 + "/accept", student, Map.of()).andExpect(status().isConflict());
        // Nobody can invite a student who already has a group (YC13).
        postJson("/api/v1/groups/" + g2 + "/invites", l2, Map.of("userId", student.getId())).andExpect(status().isConflict());
    }

    @Test
    void capacityIsCheckedAgainAtAcceptAndInvitesHoldNoSeat() throws Exception {
        User leader = user("cap-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        fill(leader, group, 3); // 4 members
        User a = user("cap-a", Role.STUDENT);
        User b = user("cap-b", Role.STUDENT);
        UUID ia = invite(leader, group, a);
        UUID ib = invite(leader, group, b); // both invited although only one seat is left

        accept(a, ia);
        getAs("/api/v1/groups/" + group, leader).andExpect(jsonPath("$.memberCount").value(5))
                .andExpect(jsonPath("$.full").value(true));
        postJson("/api/v1/invites/" + ib + "/accept", b, Map.of()).andExpect(status().isConflict());
        // A full group takes no more Apply or Invite either.
        postJson("/api/v1/groups/" + group + "/applications", user("cap-c", Role.STUDENT), Map.of())
                .andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/invites", leader, Map.of("userId", user("cap-d", Role.STUDENT).getId()))
                .andExpect(status().isConflict());
        assertThat(groupMemberRepository.countByGroupIdAndStatus(group, MemberStatus.ACTIVE)).isEqualTo(5);
    }

    @Test
    void declineAndRevokeEndTheInvite() throws Exception {
        User leader = user("dr-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User a = user("dr-a", Role.STUDENT);
        User b = user("dr-b", Role.STUDENT);
        UUID ia = invite(leader, group, a);
        UUID ib = invite(leader, group, b);
        postJson("/api/v1/invites/" + ia + "/decline", a, Map.of()).andExpect(jsonPath("$.status").value("REJECTED"));
        postJson("/api/v1/invites/" + ib + "/revoke", leader, Map.of()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        postJson("/api/v1/invites/" + ib + "/accept", b, Map.of()).andExpect(status().isConflict());
        // Someone else's invite cannot be accepted.
        UUID ic = invite(leader, group, user("dr-c", Role.STUDENT));
        postJson("/api/v1/invites/" + ic + "/accept", a, Map.of()).andExpect(status().isForbidden());
        // A student can be re-invited once the earlier invite is closed.
        invite(leader, group, a);
    }

    @Test
    void votesAreAdvisoryAndProfileVisibilityEndsWithTheApplication() throws Exception {
        User leader = user("v-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User member = user("v-member", Role.STUDENT);
        accept(member, invite(leader, group, member));
        User applicant = user("v-applicant", Role.STUDENT);
        UUID app = apply(applicant, group);

        // Members see the applicant while the Apply is alive; outsiders see nothing.
        getAs("/api/v1/groups/" + group + "/applications", member).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].student.email").value(applicant.getEmail()));
        getAs("/api/v1/groups/" + group + "/applications", user("outsider", Role.STUDENT)).andExpect(status().isForbidden());

        postJson("/api/v1/applications/" + app + "/votes", member, Map.of("vote", "OPPOSE", "comment", "not a fit"))
                .andExpect(status().isOk());
        postJson("/api/v1/applications/" + app + "/votes", leader, Map.of("vote", "SUPPORT")).andExpect(status().isOk());
        postJson("/api/v1/applications/" + app + "/votes", member, Map.of("vote", "SUPPORT")).andExpect(status().isOk());
        postJson("/api/v1/applications/" + app + "/votes", applicant, Map.of("vote", "SUPPORT")).andExpect(status().isForbidden());
        getAs("/api/v1/groups/" + group + "/applications", leader)
                .andExpect(jsonPath("$[0].supportVotes").value(2)).andExpect(jsonPath("$[0].opposeVotes").value(0));

        // The leader decides at once, whatever the votes say, and a member's vote cannot approve anything.
        postJson("/api/v1/applications/" + app + "/approve", member, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/applications/" + app + "/reject", leader, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.student").doesNotExist());
        getAs("/api/v1/groups/" + group + "/applications", member).andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].student").doesNotExist());
        postJson("/api/v1/applications/" + app + "/votes", member, Map.of("vote", "SUPPORT")).andExpect(status().isConflict());
        // The applicant still sees their own application.
        getAs("/api/v1/me/applications", applicant).andExpect(jsonPath("$[0].student.email").value(applicant.getEmail()));
    }

    @Test
    void leaderKicksBeforeLockAndEveryoneNeedsAdminAfter() throws Exception {
        User admin = user("k-admin", Role.ADMIN);
        User leader = user("k-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User a = user("k-a", Role.STUDENT);
        User b = user("k-b", Role.STUDENT);
        accept(a, invite(leader, group, a));
        accept(b, invite(leader, group, b));
        UUID memberA = groupMemberRepository.findByGroupIdAndUserId(group, a.getId()).orElseThrow().getId();
        UUID memberB = groupMemberRepository.findByGroupIdAndUserId(group, b.getId()).orElseThrow().getId();
        UUID memberLeader = groupMemberRepository.findByGroupIdAndUserId(group, leader.getId()).orElseThrow().getId();
        String base = "/api/v1/groups/" + group + "/members/";

        // A plain member cannot kick; the leader cannot kick themselves.
        mockMvc.perform(delete(base + memberB).header("Authorization", bearer(a))).andExpect(status().isForbidden());
        mockMvc.perform(delete(base + memberLeader).header("Authorization", bearer(leader))).andExpect(status().isBadRequest());
        mockMvc.perform(delete(base + memberA).header("Authorization", bearer(leader))).andExpect(status().isNoContent());

        // YC21: the kicked student can find and join another group.
        User otherLeader = user("k-leader2", Role.STUDENT);
        UUID other = createGroup(otherLeader);
        accept(a, invite(otherLeader, other, a));

        // Locked: the leader can no longer change the roster, an Admin still can (YC19).
        postJson("/api/v1/groups/" + group + "/lock", leader, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/lock", admin, Map.of()).andExpect(jsonPath("$.locked").value(true));
        mockMvc.perform(delete(base + memberB).header("Authorization", bearer(leader))).andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/invites", leader, Map.of("userId", user("k-c", Role.STUDENT).getId()))
                .andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/applications", user("k-d", Role.STUDENT), Map.of())
                .andExpect(status().isConflict());
        mockMvc.perform(delete(base + memberB).header("Authorization", bearer(admin))).andExpect(status().isNoContent());
        // A locked group is no longer offered to students looking for one.
        getAs("/api/v1/groups?available=true&size=1000", user("k-e", Role.STUDENT))
                .andExpect(jsonPath("$.content[?(@.id=='" + group + "')]").isEmpty());
    }

    @Test
    void memberLeavesWithLeaderApprovalBeforeLockOnly() throws Exception {
        User admin = user("lv-admin", Role.ADMIN);
        User leader = user("lv-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User a = user("lv-a", Role.STUDENT);
        User b = user("lv-b", Role.STUDENT);
        accept(a, invite(leader, group, a));
        accept(b, invite(leader, group, b));

        postJson("/api/v1/groups/" + group + "/leave-requests", leader, Map.of("reason", "x")).andExpect(status().isBadRequest());
        postJson("/api/v1/groups/" + group + "/leave-requests", user("lv-out", Role.STUDENT), Map.of())
                .andExpect(status().isForbidden());
        String leaveId = body(postJson("/api/v1/groups/" + group + "/leave-requests", a, Map.of("reason", "overloaded"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))).get("id").asText();
        postJson("/api/v1/groups/" + group + "/leave-requests", a, Map.of()).andExpect(status().isConflict());
        // The member is still in the group until the leader approves.
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, a.getId(), MemberStatus.ACTIVE)).isTrue();
        postJson("/api/v1/leave-requests/" + leaveId + "/approve", b, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/leave-requests/" + leaveId + "/approve", leader, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, a.getId(), MemberStatus.ACTIVE)).isFalse();

        // Rejected and withdrawn requests change nothing.
        String second = body(postJson("/api/v1/groups/" + group + "/leave-requests", b, Map.of())
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/leave-requests/" + second + "/reject", leader, Map.of()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(groupMemberRepository.existsByGroupIdAndUserIdAndStatus(group, b.getId(), MemberStatus.ACTIVE)).isTrue();
        String third = body(postJson("/api/v1/groups/" + group + "/leave-requests", b, Map.of())
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/leave-requests/" + third + "/withdraw", b, Map.of()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        String fourth = body(postJson("/api/v1/groups/" + group + "/leave-requests", b, Map.of())
                .andExpect(status().isCreated())).get("id").asText();

        // After Locked a member cannot ask to leave and a pending request cannot be approved: Admin takes over.
        postJson("/api/v1/groups/" + group + "/lock", admin, Map.of()).andExpect(status().isOk());
        postJson("/api/v1/leave-requests/" + fourth + "/approve", leader, Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/groups/" + group + "/leave-requests", user("lv-new", Role.STUDENT), Map.of())
                .andExpect(status().isForbidden());
        getAs("/api/v1/groups/" + group + "/leave-requests", leader).andExpect(status().isOk());
        assertThat(userRepository.findById(a.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
    }

    @Test
    void adminReplacesLeaderAndOnlyAMemberCanBecomeLeader() throws Exception {
        User admin = user("rl-admin", Role.ADMIN);
        User leader = user("rl-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User a = user("rl-a", Role.STUDENT);
        accept(a, invite(leader, group, a));
        String url = "/api/v1/groups/" + group + "/leader";

        mockMvc.perform(put(url).header("Authorization", bearer(leader)).contentType("application/json")
                .content("{\"userId\":\"" + a.getId() + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(put(url).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"userId\":\"" + user("rl-out", Role.STUDENT).getId() + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"userId\":\"" + a.getId() + "\"}")).andExpect(status().isOk());

        assertThat(userRepository.findById(a.getId()).orElseThrow().getRole()).isEqualTo(Role.GROUP_LEADER);
        assertThat(userRepository.findById(leader.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        assertThat(groupMemberRepository.findByGroupIdAndStatus(group, MemberStatus.ACTIVE)
                .stream().filter(GroupMember::isLeader)).hasSize(1);
        // The former leader lost their powers; the new one has them.
        postJson("/api/v1/groups/" + group + "/invites", leader, Map.of("userId", user("rl-b", Role.STUDENT).getId()))
                .andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/invites", a, Map.of("userId", user("rl-c", Role.STUDENT).getId()))
                .andExpect(status().isCreated());
    }

    @Test
    void leaderSubmitsRosterOfThreeToFiveAndSupervisorReviewsIt() throws Exception {
        User admin = user("ro-admin", Role.ADMIN);
        User supervisor = user("ro-sup", Role.INSTRUCTOR);
        User leader = user("ro-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        String submit = "/api/v1/groups/" + group + "/roster/submit";
        String review = "/api/v1/groups/" + group + "/roster/review";

        // Too small (YC06) and no supervisor yet.
        postJson(submit, leader, Map.of()).andExpect(status().isConflict());
        fill(leader, group, 2);
        getAs("/api/v1/groups/" + group, leader).andExpect(jsonPath("$.meetsMinimum").value(true));
        postJson(submit, leader, Map.of()).andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/groups/" + group).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"supervisorId\":\"" + supervisor.getId() + "\",\"status\":\"FORMED\"}")).andExpect(status().isOk());

        postJson(submit, leader, Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.rosterStatus").value("SUBMITTED"));
        postJson(submit, leader, Map.of()).andExpect(status().isConflict());
        // Only the group's own supervisor (or Admin) reviews it; a rejection needs a reason.
        postJson(review, user("ro-other", Role.INSTRUCTOR), Map.of("approved", true)).andExpect(status().isForbidden());
        postJson(review, leader, Map.of("approved", true)).andExpect(status().isForbidden());
        postJson(review, supervisor, Map.of("approved", false)).andExpect(status().isBadRequest());
        postJson(review, supervisor, Map.of("approved", false, "note", "Need a stronger team"))
                .andExpect(jsonPath("$.rosterStatus").value("REJECTED")).andExpect(jsonPath("$.rosterNote").value("Need a stronger team"));
        postJson(submit, leader, Map.of()).andExpect(jsonPath("$.rosterStatus").value("SUBMITTED"));
        postJson(review, supervisor, Map.of("approved", true)).andExpect(jsonPath("$.rosterStatus").value("APPROVED"));

        // Joining an official member is separate from roster approval, and a roster change voids the approval.
        User late = user("ro-late", Role.STUDENT);
        accept(late, invite(leader, group, late));
        getAs("/api/v1/groups/" + group, leader).andExpect(jsonPath("$.rosterStatus").value("DRAFT"));
    }

    @Test
    void adminImportsStudentListFlagsAndClearsIneligibleStudents() throws Exception {
        User admin = user("el-admin", Role.ADMIN);
        User existing = user("el-existing", Role.STUDENT);
        String newEmail = "el-new-" + suffix + "@fpt.edu.vn";
        postJson("/api/v1/eligibility/import", existing, Map.of("students", List.of())).andExpect(status().isForbidden());
        postJson("/api/v1/eligibility/import", admin, Map.of("students", List.of(
                Map.of("email", existing.getEmail(), "eligible", false, "reason", "Owes credits"),
                Map.of("email", newEmail, "fullName", "New Student", "eligible", true),
                Map.of("email", admin.getEmail(), "eligible", false),
                Map.of("email", "not-an-email", "eligible", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.flagged").value(1)).andExpect(jsonPath("$.invalid.length()").value(2));
        assertThat(userRepository.findByEmailIgnoreCase(newEmail)).isPresent();

        // YC04: flagged students can sign in and read their status, but cannot create, apply or accept.
        UUID group = createGroup(user("el-leader", Role.STUDENT));
        getAs("/api/v1/auth/me", existing).andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false));
        postJson("/api/v1/groups", existing, Map.of("semester", "S-" + suffix)).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/applications", existing, Map.of()).andExpect(status().isForbidden());
        getAs("/api/v1/eligibility/ineligible", admin).andExpect(status().isOk());

        // Clearing the flag restores everything.
        mockMvc.perform(put("/api/v1/eligibility/" + existing.getId()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"eligible\":true}")).andExpect(status().isOk());
        apply(existing, group);
    }

    @Test
    void flaggingAStudentCancelsTheirOpenRequestsAndBlocksAcceptingInvites() throws Exception {
        User admin = user("fl-admin", Role.ADMIN);
        User leader = user("fl-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User student = user("fl-student", Role.STUDENT);
        UUID invite = invite(leader, group, student);
        UUID app = apply(student, createGroup(user("fl-leader2", Role.STUDENT)));

        mockMvc.perform(put("/api/v1/eligibility/" + student.getId()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"eligible\":false,\"reason\":\"Suspended\"}")).andExpect(status().isOk());
        assertThat(joinRequests.findById(invite).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        assertThat(joinRequests.findById(app).orElseThrow().getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        postJson("/api/v1/groups/" + group + "/invites", leader, Map.of("userId", student.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentsOnlyReachTheirOwnFormationEndpoints() throws Exception {
        User student = user("scope-student", Role.STUDENT);
        getAs("/api/v1/users", student).andExpect(status().isForbidden());
        getAs("/api/v1/notifications", student).andExpect(status().isOk());
        postJson("/api/v1/semesters/x/join-settings", student, Map.of()).andExpect(status().is4xxClientError());
        UUID group = createGroup(user("scope-leader", Role.STUDENT));
        getAs("/api/v1/groups?available=true&size=1000", student).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + group + "')]").isNotEmpty());
        getAs("/api/v1/groups/" + group, student).andExpect(status().isForbidden());
        getAs("/api/v1/groups/" + group + "/invites", student).andExpect(status().isForbidden());
    }

    private com.fasterxml.jackson.databind.JsonNode notifications(User as) throws Exception {
        return body(getAs("/api/v1/notifications", as).andExpect(status().isOk()));
    }

    private boolean hasNotification(User as, String type) throws Exception {
        for (var n : notifications(as).get("content")) {
            if (type.equals(n.get("type").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void joinEventsNotifyTheRightPeople() throws Exception {
        User leader = user("n-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User applicant = user("n-applicant", Role.STUDENT);
        UUID app = apply(applicant, group);
        assertThat(hasNotification(leader, "JOIN_APPLICATION_RECEIVED")).isTrue();
        assertThat(hasNotification(applicant, "JOIN_APPLICATION_RECEIVED")).isFalse();

        postJson("/api/v1/applications/" + app + "/approve", leader, Map.of()).andExpect(status().isOk());
        assertThat(hasNotification(leader, "MEMBER_JOINED")).isTrue();

        User invitee = user("n-invitee", Role.STUDENT);
        UUID invite = invite(leader, group, invitee);
        assertThat(hasNotification(invitee, "JOIN_INVITE_RECEIVED")).isTrue();
        accept(invitee, invite);

        User declined = user("n-declined", Role.STUDENT);
        UUID declinedInvite = invite(leader, group, declined);
        postJson("/api/v1/invites/" + declinedInvite + "/decline", declined, Map.of()).andExpect(status().isOk());
        assertThat(hasNotification(leader, "JOIN_INVITE_DECLINED")).isTrue();

        User rejectedApplicant = user("n-rejected", Role.STUDENT);
        UUID rejectedApp = apply(rejectedApplicant, group);
        postJson("/api/v1/applications/" + rejectedApp + "/reject", leader, Map.of()).andExpect(status().isOk());
        assertThat(hasNotification(rejectedApplicant, "JOIN_APPLICATION_REJECTED")).isTrue();
    }

    @Test
    void leaveKickAndRosterEventsAreNotified() throws Exception {
        User supervisor = user("e-sup", Role.INSTRUCTOR);
        User admin = user("e-admin", Role.ADMIN);
        User leader = user("e-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User leaver = user("e-leaver", Role.STUDENT);
        User kicked = user("e-kicked", Role.STUDENT);
        accept(leaver, invite(leader, group, leaver));
        accept(kicked, invite(leader, group, kicked));

        String leave = body(postJson("/api/v1/groups/" + group + "/leave-requests", leaver, Map.of("reason", "busy"))
                .andExpect(status().isCreated())).get("id").asText();
        assertThat(hasNotification(leader, "LEAVE_REQUESTED")).isTrue();
        postJson("/api/v1/leave-requests/" + leave + "/approve", leader, Map.of()).andExpect(status().isOk());
        assertThat(hasNotification(leaver, "LEAVE_DECIDED")).isTrue();

        UUID kickedMember = groupMemberRepository.findByGroupIdAndUserId(group, kicked.getId()).orElseThrow().getId();
        mockMvc.perform(delete("/api/v1/groups/" + group + "/members/" + kickedMember)
                .header("Authorization", bearer(leader))).andExpect(status().isNoContent());
        assertThat(hasNotification(kicked, "MEMBER_REMOVED")).isTrue();

        fill(leader, group, 2);
        mockMvc.perform(put("/api/v1/groups/" + group).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"supervisorId\":\"" + supervisor.getId() + "\",\"status\":\"FORMED\"}")).andExpect(status().isOk());
        postJson("/api/v1/groups/" + group + "/roster/submit", leader, Map.of()).andExpect(status().isOk());
        assertThat(hasNotification(supervisor, "ROSTER_SUBMITTED")).isTrue();
        postJson("/api/v1/groups/" + group + "/roster/review", supervisor, Map.of("approved", true)).andExpect(status().isOk());
        assertThat(hasNotification(leader, "ROSTER_REVIEWED")).isTrue();
    }

    @Test
    void supervisorReportsRosterChangeAndAdminResolvesIt() throws Exception {
        User supervisor = user("r-sup", Role.INSTRUCTOR);
        User admin = user("r-admin", Role.ADMIN);
        User leader = user("r-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        mockMvc.perform(put("/api/v1/groups/" + group).header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"supervisorId\":\"" + supervisor.getId() + "\",\"status\":\"FORMED\"}")).andExpect(status().isOk());
        String url = "/api/v1/groups/" + group + "/roster-change-reports";
        Map<String, Object> payload = Map.of("type", "LEADER_REPLACEMENT", "description", "Leader is unreachable");

        postJson(url, user("r-other", Role.INSTRUCTOR), payload).andExpect(status().isForbidden());
        postJson(url, leader, payload).andExpect(status().isForbidden());
        postJson(url, supervisor, Map.of("type", "MEMBER_CHANGE", "description", " ")).andExpect(status().isBadRequest());
        String id = body(postJson(url, supervisor, payload).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))).get("id").asText();
        assertThat(hasNotification(admin, "ROSTER_CHANGE_REPORTED")).isTrue();

        getAs("/api/v1/roster-change-reports?status=OPEN", supervisor).andExpect(status().isForbidden());
        getAs("/api/v1/roster-change-reports?status=OPEN", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')]").isNotEmpty());
        getAs(url, supervisor).andExpect(jsonPath("$[0].id").value(id));
        postJson("/api/v1/roster-change-reports/" + id + "/resolve", supervisor, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/roster-change-reports/" + id + "/resolve", admin, Map.of("note", "Replaced"))
                .andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.resolutionNote").value("Replaced"));
        postJson("/api/v1/roster-change-reports/" + id + "/resolve", admin, Map.of()).andExpect(status().isConflict());
    }

    @Test
    void recruitingProfileIsSeenByTheGroupOnlyWhileTheApplyIsOpen() throws Exception {
        User leader = user("p-leader", Role.STUDENT);
        UUID group = createGroup(leader);
        User applicant = user("p-applicant", Role.STUDENT);
        mockMvc.perform(put("/api/v1/me/profile").header("Authorization", bearer(applicant)).contentType("application/json")
                .content("{\"bio\":\"Backend dev\",\"skills\":\"Java, Spring\"}")).andExpect(status().isOk());
        UUID app = apply(applicant, group);
        getAs("/api/v1/groups/" + group + "/applications", leader)
                .andExpect(jsonPath("$[0].student.bio").value("Backend dev"))
                .andExpect(jsonPath("$[0].student.skills").value("Java, Spring"));
        postJson("/api/v1/applications/" + app + "/reject", leader, Map.of()).andExpect(status().isOk());
        getAs("/api/v1/groups/" + group + "/applications", leader).andExpect(jsonPath("$[0].student").doesNotExist());
    }
}
