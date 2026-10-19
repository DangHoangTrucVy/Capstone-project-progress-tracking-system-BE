package com.capstone.tracking.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Creating groups, Apply / Invite, capacity, lock, leaving and the automatic Leader (6, 8). */
class GroupFormationIntegrationTest extends TestSupport {

    @Test
    void applyingAndBeingAcceptedJoinsAtOnceAndCancelsTheOtherRequests() throws Exception {
        World w = world(4, 0);
        User leaderA = w.students().get(0);
        User leaderB = w.students().get(1);
        User applicant = w.students().get(2);
        UUID groupA = createGroup(w, leaderA, "Alpha");
        UUID groupB = createGroup(w, leaderB, "Beta");
        // A Leader already has a group in this subject.
        postJson("/api/v1/subjects/" + w.subjectId() + "/groups", leaderA, Map.of("name", "Again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ALREADY_IN_GROUP"));

        UUID applyA = id(postJson("/api/v1/groups/" + groupA + "/applications", applicant, Map.of("message", "Hi"))
                .andExpect(status().isCreated()));
        UUID applyB = id(postJson("/api/v1/groups/" + groupB + "/applications", applicant, Map.of())
                .andExpect(status().isCreated()));
        postJson("/api/v1/groups/" + groupA + "/applications", applicant, Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DUPLICATE_REQUEST"));
        // Only the Leader accepts.
        postJson("/api/v1/requests/" + applyA + "/accept", leaderB, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/requests/" + applyA + "/accept", leaderA, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        getAs("/api/v1/requests/" + applyB, applicant).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("JOINED_ANOTHER_GROUP"));
        getAs("/api/v1/groups/" + groupA, applicant).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.state").value("OPEN"));
    }

    @Test
    void atMostThreePendingApplicationsAndInvitesDoNotCount() throws Exception {
        World w = world(5, 0);
        User applicant = w.students().get(4);
        for (int i = 0; i < 3; i++) {
            UUID g = createGroup(w, w.students().get(i), "G" + i);
            postJson("/api/v1/groups/" + g + "/applications", applicant, Map.of()).andExpect(status().isCreated());
        }
        UUID fourth = createGroup(w, w.students().get(3), "G3");
        postJson("/api/v1/groups/" + fourth + "/applications", applicant, Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("TOO_MANY_APPLIES"));
        UUID invite = id(postJson("/api/v1/groups/" + fourth + "/invitations", w.students().get(3),
                Map.of("studentCode", applicant.getStudentCode())).andExpect(status().isCreated()));
        postJson("/api/v1/requests/" + invite + "/accept", applicant, Map.of()).andExpect(status().isOk());
        assertThat(body(getAs("/api/v1/subjects/" + w.subjectId() + "/my-requests", applicant)).toString())
                .contains("CANCELLED").doesNotContain("PENDING");
    }

    @Test
    void groupsStayWithinOneMajorAndHoldAtMostFive() throws Exception {
        World w = world(7, 1);
        User leader = w.students().get(0);
        UUID group = createGroup(w, leader, "Full");
        postJson("/api/v1/groups/" + group + "/applications", w.others().get(0), Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OTHER_MAJOR"));
        postJson("/api/v1/groups/" + group + "/invitations", leader, Map.of("studentId", w.others().get(0).getId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OTHER_MAJOR"));
        UUID pending = id(postJson("/api/v1/groups/" + group + "/applications", w.students().get(6), Map.of())
                .andExpect(status().isCreated()));
        for (int i = 1; i <= 4; i++) {
            join(group, leader, w.students().get(i));
        }
        getAs("/api/v1/groups/" + group, leader).andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.state").value("FULL")).andExpect(jsonPath("$.full").value(true));
        postJson("/api/v1/requests/" + pending + "/accept", leader, Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("GROUP_FULL"));
        postJson("/api/v1/groups/" + group + "/applications", w.students().get(5), Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("GROUP_FULL"));
    }

    @Test
    void aLockedGroupTakesNoApplicationsButStillInvitesAndLeavingNeedsTheLeader() throws Exception {
        World w = world(4, 0);
        User leader = w.students().get(0);
        User member = w.students().get(1);
        UUID group = createGroup(w, leader, "Locked");
        join(group, leader, member);
        UUID earlyApply = id(postJson("/api/v1/groups/" + group + "/applications", w.students().get(2), Map.of())
                .andExpect(status().isCreated()));
        postJson("/api/v1/groups/" + group + "/lock", member, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/groups/" + group + "/lock", leader, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LOCKED")).andExpect(jsonPath("$.locked").value(true));
        postJson("/api/v1/groups/" + group + "/applications", w.students().get(3), Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("GROUP_LOCKED"));
        // An application made before the lock can still be accepted; invitations still go out.
        postJson("/api/v1/requests/" + earlyApply + "/accept", leader, Map.of()).andExpect(status().isOk());
        postJson("/api/v1/groups/" + group + "/invitations", leader, Map.of("studentId", w.students().get(3).getId()))
                .andExpect(status().isCreated());

        // Leaving a locked group: a request to the Leader; the Leader refuses; the member complains.
        JsonNode leave = body(postJson("/api/v1/groups/" + group + "/leave", member, Map.of("reason", "Đổi nhóm"))
                .andExpect(status().isOk()));
        assertThat(leave.get("left").asBoolean()).isFalse();
        String requestId = leave.get("request").get("id").asText();
        assertThat(leave.get("request").get("status").asText()).isEqualTo("PENDING_LEADER");
        postJson("/api/v1/leave-requests/" + requestId + "/leader-decision", leader, Map.of("approve", false, "note", "Không"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        postJson("/api/v1/leave-requests/" + requestId + "/complaint", member, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_MODERATOR"))
                .andExpect(jsonPath("$.complaint").value(true));
        getAs("/api/v1/subjects/" + w.subjectId() + "/complaints", w.moderator())
                .andExpect(jsonPath("$[0].id").value(requestId));
        postJson("/api/v1/leave-requests/" + requestId + "/moderator-decision", w.moderator(), Map.of("approve", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(body(getAs("/api/v1/groups/" + group, leader)).get("size").asInt()).isEqualTo(2);

        // The Leader of a locked group must transfer the role or unlock before leaving.
        postJson("/api/v1/groups/" + group + "/leave", leader, Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LEADER_MUST_TRANSFER"));
    }

    @Test
    void aLeavingLeaderIsReplacedByTheEarliestMemberAndTheLastOneDissolvesTheGroup() throws Exception {
        World w = world(3, 0);
        User leader = w.students().get(0);
        UUID group = createGroup(w, leader, "Leaving");
        join(group, leader, w.students().get(1));
        join(group, leader, w.students().get(2));
        postJson("/api/v1/groups/" + group + "/leave", leader, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.left").value(true));
        getAs("/api/v1/groups/" + group, w.students().get(1))
                .andExpect(jsonPath("$.leaderId").value(w.students().get(1).getId().toString()));
        // The Leader removes a member, then leaves as the last member: the group is dissolved.
        deleteAs("/api/v1/groups/" + group + "/members/" + w.students().get(2).getId(), w.students().get(1))
                .andExpect(status().isNoContent());
        postJson("/api/v1/groups/" + group + "/leave", w.students().get(1), Map.of()).andExpect(status().isOk());
        getAs("/api/v1/groups/" + group, w.moderator()).andExpect(jsonPath("$.state").value("DISSOLVED"))
                .andExpect(jsonPath("$.dissolvedReason").value("EMPTY"));
    }

    @Test
    void theLeaderRoleIsTransferredOnlyWhenTheRecipientAccepts() throws Exception {
        World w = world(3, 0);
        User leader = w.students().get(0);
        User next = w.students().get(1);
        UUID group = createGroup(w, leader, "Transfer");
        join(group, leader, next);
        join(group, leader, w.students().get(2));
        String transfer = body(postJson("/api/v1/groups/" + group + "/leader-transfers", leader,
                Map.of("toUserId", next.getId(), "leaveAfter", true)).andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/leader-transfers/" + transfer + "/accept", w.students().get(2), Map.of())
                .andExpect(status().isForbidden());
        postJson("/api/v1/leader-transfers/" + transfer + "/accept", next, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        JsonNode g = body(getAs("/api/v1/groups/" + group, next));
        assertThat(g.get("leaderId").asText()).isEqualTo(next.getId().toString());
        assertThat(g.get("size").asInt()).isEqualTo(2); // transferred and left in one step
    }

    @Test
    void privateProfilesShowOnlyNameAndCodeExceptToTheGroupAnApplicationIsPending() throws Exception {
        World w = world(3, 0);
        User leader = w.students().get(0);
        User hidden = w.students().get(1);
        hidden.setProfilePrivate(true);
        hidden.setSkills("Kotlin secret");
        userRepository.save(hidden);
        UUID group = createGroup(w, leader, "Profiles");

        List<?> bySkill = objectMapper.convertValue(body(getAs("/api/v1/subjects/" + w.subjectId()
                + "/candidates?skill=kotlin", leader)), List.class);
        assertThat(bySkill).isEmpty(); // hidden skills never match
        JsonNode byName = body(getAs("/api/v1/subjects/" + w.subjectId() + "/candidates?q=" + hidden.getStudentCode(), leader));
        assertThat(byName.get(0).get("full").asBoolean()).isFalse();
        assertThat(byName.get(0).has("skills")).isFalse();

        UUID apply = id(postJson("/api/v1/groups/" + group + "/applications", hidden, Map.of()).andExpect(status().isCreated()));
        getAs("/api/v1/requests/" + apply, leader).andExpect(jsonPath("$.student.full").value(true))
                .andExpect(jsonPath("$.student.skills").value("Kotlin secret"));
        putJson("/api/v1/requests/" + apply + "/like", leader, Map.of()).andExpect(jsonPath("$.likes").value(1));
        postJson("/api/v1/requests/" + apply + "/withdraw", hidden, Map.of()).andExpect(status().isOk());
        // The right to the full profile ends with the application.
        getAs("/api/v1/requests/" + apply, leader).andExpect(jsonPath("$.student.full").value(false));
    }

    @Test
    void anOlderSemesterIsViewOnlyForAStudentRetakingTheSubject() throws Exception {
        World w = world(1, 0);
        User student = w.students().get(0);
        Semester later = new Semester(freeSemesterCode(), "Retake semester", LocalDate.now().plusDays(70),
                LocalDate.now().plusDays(180));
        semesterRepository.save(later);
        semesterStudentRepository.save(SemesterStudent.builder().semesterCode(later.getCode()).user(student)
                .major(w.major()).currentTerm(8).build());
        postJson("/api/v1/subjects/" + w.subjectId() + "/groups", student, Map.of("name", "Old"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("READ_ONLY_SEMESTER"));
        getAs("/api/v1/me/semesters", student).andExpect(jsonPath("$[0].semesterCode").value(later.getCode()))
                .andExpect(jsonPath("$[1].readOnly").value(true));
    }

    @Test
    void anIneligibleStudentIsTakenOutAndTheGroupGetsANewLeader() throws Exception {
        World w = world(3, 0);
        User leader = w.students().get(0);
        UUID group = createGroup(w, leader, "Eligibility");
        join(group, leader, w.students().get(1));
        join(group, leader, w.students().get(2));
        UUID enrollment = enrollmentRepository.findBySubjectIdAndUserId(w.subjectId(), leader.getId()).orElseThrow().getId();
        putJson("/api/v1/enrollments/" + enrollment + "/eligibility", w.moderator(),
                Map.of("eligibility", "INELIGIBLE")).andExpect(status().isBadRequest()); // reason required
        putJson("/api/v1/enrollments/" + enrollment + "/eligibility", w.moderator(),
                Map.of("eligibility", "INELIGIBLE", "reason", "Nợ môn")).andExpect(status().isOk());
        JsonNode g = body(getAs("/api/v1/groups/" + group, w.moderator()));
        assertThat(g.get("size").asInt()).isEqualTo(2);
        assertThat(g.get("leaderId").asText()).isEqualTo(w.students().get(1).getId().toString());
        // Ineligible students only view.
        postJson("/api/v1/subjects/" + w.subjectId() + "/groups", leader, Map.of("name", "New"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("NOT_ELIGIBLE"));
        getAs("/api/v1/subjects/" + w.subjectId() + "/students?hasGroup=false", w.moderator())
                .andExpect(jsonPath("$.content[0].noGroupReason").value("INELIGIBLE"));
    }
}
