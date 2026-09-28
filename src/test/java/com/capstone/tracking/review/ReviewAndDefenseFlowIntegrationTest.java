package com.capstone.tracking.review;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Giai đoạn 5 (Reviews 1-3, closed council) and 6 (rolling defenses, attempts 1 and 2). */
class ReviewAndDefenseFlowIntegrationTest extends WorkflowTestSupport {

    private User admin;
    private User supervisor;
    private User r1;
    private User r2;
    private User r3;
    private User leaderA;
    private StudentGroup groupA;
    private StudentGroup groupB;
    private StudentGroup groupC;
    private Instant base;
    private String room;

    @BeforeEach
    void setUp() {
        admin = user("rv-admin", Role.ADMIN);
        supervisor = user("rv-gv", Role.INSTRUCTOR);
        r1 = user("rv-r1", Role.INSTRUCTOR);
        r2 = user("rv-r2", Role.COUNCIL);
        r3 = user("rv-r3", Role.COUNCIL);
        groupA = group("RVA", supervisor, true);
        groupB = group("RVB", supervisor, true);
        groupC = group("RVC", supervisor, true);
        leaderA = user("rv-leader-a", Role.GROUP_LEADER);
        join(groupA, leaderA, true);
        join(groupA, user("rv-member-a", Role.STUDENT), false);
        join(groupC, user("rv-leader-c", Role.GROUP_LEADER), true);
        // A far-away, per-run day so rooms and panels never collide with other tests on the shared DB.
        base = Instant.now().plus(200 + Math.abs(suffix.hashCode() % 2000), ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        room = "P-" + suffix;
    }

    @Test
    void reviewsAreScheduledClonedAndClosedCouncilSortsGroups() throws Exception {
        postJson("/api/v1/reviews", admin, review(groupA, "REVIEW_1", base, List.of(r1, r2), null))
                .andExpect(status().isCreated());
        // Same reviewers, same time -> conflict; an hour later is fine.
        postJson("/api/v1/reviews", admin, review(groupB, "REVIEW_1", base.plus(Duration.ofMinutes(30)), List.of(r1, r2), null))
                .andExpect(status().isConflict());
        postJson("/api/v1/reviews", admin, review(groupB, "REVIEW_1", base.plus(Duration.ofHours(1)), List.of(r1, r2), null))
                .andExpect(status().isCreated());
        getAs("/api/v1/groups/" + groupA.getId() + "/reviews", leaderA)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].round").value("REVIEW_1"));

        // Review 2 is cloned from Review 1, four weeks later, with the same panel.
        JsonNode cloned = body(postJson("/api/v1/reviews/clone", admin, Map.of("semester", groupA.getSemester(),
                "fromRound", "REVIEW_1", "toRound", "REVIEW_2", "offsetDays", 28)).andExpect(status().isOk()));
        assertThat(cloned).hasSize(2);
        assertThat(Instant.parse(cloned.get(0).get("scheduledAt").asText())).isEqualTo(base.plus(Duration.ofDays(28)));
        assertThat(cloned.get(0).get("panel")).hasSize(2);

        // Review 3: exactly 3 lecturers, one of them chair.
        Instant r3Time = base.plus(Duration.ofDays(77));
        postJson("/api/v1/reviews", admin, review(groupA, "REVIEW_3", r3Time, List.of(r1, r2), r1))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/reviews", admin, review(groupA, "REVIEW_3", r3Time, List.of(r1, r2, r3), null))
                .andExpect(status().isBadRequest());
        String council = body(postJson("/api/v1/reviews", admin, review(groupA, "REVIEW_3", r3Time, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.panel[0].chair").value(true))).get("id").asText();

        // Only the chair records Review 3, and it needs an outcome.
        postJson("/api/v1/reviews/" + council + "/result", r1,
                Map.of("feedback", "x", "outcome", "READY_FOR_DEFENSE_1")).andExpect(status().isForbidden());
        postJson("/api/v1/reviews/" + council + "/result", r2, Map.of("feedback", "x")).andExpect(status().isBadRequest());
        postJson("/api/v1/reviews/" + council + "/result", r2, Map.of("feedback", "Bổ sung phần kiểm thử",
                        "outcome", "REVISE_BEFORE_DEFENSE_1", "revisionDeadline", r3Time.plus(Duration.ofDays(7)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVISE_BEFORE_DEFENSE_1"));

        EmailMessage email = lastEmailFor(groupA, "Cần chỉnh sửa");
        assertThat(email.to()).containsExactly(leaderA.getEmail());
        assertThat(email.body()).contains("Bổ sung phần kiểm thử").contains("Hạn chót");
        getAs("/api/v1/groups/" + groupA.getId() + "/overview", leaderA)
                .andExpect(jsonPath("$.defenseTrack").value("REVISE_BEFORE_DEFENSE_1"));

        // Not cleared for Defense 1 until the revision is confirmed.
        postJson("/api/v1/defenses", admin, defense(groupA, 1, r3Time.plus(Duration.ofDays(14)))).andExpect(status().isBadRequest());
        postJson("/api/v1/reviews/" + council + "/revision-complete", leaderA, Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/reviews/" + council + "/revision-complete", supervisor, Map.of()).andExpect(status().isOk());
        postJson("/api/v1/defenses", admin, defense(groupA, 1, r3Time.plus(Duration.ofDays(14)))).andExpect(status().isCreated());
        getAs("/api/v1/groups/" + groupA.getId() + "/overview", leaderA)
                .andExpect(jsonPath("$.defenseTrack").value("DEFENSE_1"))
                .andExpect(jsonPath("$.defenses[0].room").value(room));
    }

    @Test
    void defensesRollThroughOneRoomAndSecondFailureFailsTheGroup() throws Exception {
        closedCouncil(groupA, "READY_FOR_DEFENSE_1", 0);
        closedCouncil(groupB, "DEFER_TO_DEFENSE_2", 1);
        closedCouncil(groupC, "READY_FOR_DEFENSE_1", 2);
        Instant day = base.plus(Duration.ofDays(20));

        // Rolling schedule is all-or-nothing: B was deferred to Defense 2.
        postJson("/api/v1/defenses/rolling", admin, rolling(List.of(groupA, groupB), day)).andExpect(status().isBadRequest());
        getAs("/api/v1/groups/" + groupA.getId() + "/defenses", admin).andExpect(jsonPath("$.length()").value(0));

        JsonNode rolled = body(postJson("/api/v1/defenses/rolling", admin, rolling(List.of(groupA, groupC), day))
                .andExpect(status().isCreated()));
        assertThat(Instant.parse(rolled.get(1).get("scheduledAt").asText())).isEqualTo(day.plus(Duration.ofMinutes(60 + 15)));
        String defenseA = rolled.get(0).get("id").asText();
        String defenseC = rolled.get(1).get("id").asText();

        // The room is busy while A defends; B (deferred) goes straight to attempt 2, not attempt 1.
        Map<String, Object> clash = defense(groupB, 2, day.plus(Duration.ofMinutes(30)));
        clash.put("committeeIds", List.of(supervisor.getId()));
        clash.put("chairId", supervisor.getId());
        postJson("/api/v1/defenses", admin, clash).andExpect(status().isConflict());
        postJson("/api/v1/defenses", admin, defense(groupB, 1, day.plus(Duration.ofDays(1)))).andExpect(status().isBadRequest());
        postJson("/api/v1/defenses", admin, defense(groupB, 2, day.plus(Duration.ofDays(1)))).andExpect(status().isCreated());

        // Only the chair grades. A fails attempt 1 -> attempt 2 -> fails again -> group FAILED.
        postJson("/api/v1/defenses/" + defenseA + "/result", r1, Map.of("passed", false, "score", 4.0))
                .andExpect(status().isForbidden());
        postJson("/api/v1/defenses/" + defenseA + "/result", r3, Map.of("passed", false, "score", 4.0, "feedback", "Chưa đạt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        getAs("/api/v1/groups/" + groupA.getId() + "/overview", leaderA).andExpect(jsonPath("$.defenseTrack").value("DEFENSE_2"));
        String retry = body(postJson("/api/v1/defenses", admin, defense(groupA, 2, day.plus(Duration.ofDays(2))))
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/defenses/" + retry + "/result", r3, Map.of("passed", false, "score", 3.5))
                .andExpect(status().isOk());
        assertThat(studentGroupRepository.findById(groupA.getId()).orElseThrow().getStatus()).isEqualTo(GroupStatus.FAILED);
        assertThat(lastEmailFor(groupA, "Fail đồ án").to()).containsExactly(leaderA.getEmail());

        postJson("/api/v1/defenses/" + defenseC + "/result", r3, Map.of("passed", true, "score", 8.5))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASSED"));
        assertThat(studentGroupRepository.findById(groupC.getId()).orElseThrow().getStatus()).isEqualTo(GroupStatus.COMPLETED);
    }

    private void closedCouncil(StudentGroup group, String outcome, int slot) throws Exception {
        String id = body(postJson("/api/v1/reviews", admin,
                review(group, "REVIEW_3", base.plus(Duration.ofHours(slot)), List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/reviews/" + id + "/result", r2, Map.of("feedback", "HĐ kín", "outcome", outcome))
                .andExpect(status().isOk());
    }

    private Map<String, Object> review(StudentGroup group, String round, Instant at, List<User> panel, User chair) {
        Map<String, Object> m = new HashMap<>(Map.of("groupId", group.getId(), "round", round, "scheduledAt", at.toString(),
                "durationMinutes", 45, "location", "R-" + suffix, "reviewerIds", panel.stream().map(User::getId).toList()));
        if (chair != null) {
            m.put("chairId", chair.getId());
        }
        return m;
    }

    private Map<String, Object> defense(StudentGroup group, int attempt, Instant at) {
        return new HashMap<>(Map.of("groupId", group.getId(), "attempt", attempt, "scheduledAt", at.toString(),
                "durationMinutes", 60, "room", room, "committeeIds", List.of(r1.getId(), r2.getId(), r3.getId()),
                "chairId", r3.getId()));
    }

    private Map<String, Object> rolling(List<StudentGroup> groups, Instant first) {
        return Map.of("attempt", 1, "groupIds", groups.stream().map(StudentGroup::getId).toList(),
                "firstStartTime", first.toString(), "durationMinutes", 60, "breakMinutes", 15, "room", room,
                "committeeIds", List.of(r1.getId(), r2.getId(), r3.getId()), "chairId", r3.getId());
    }

    private EmailMessage lastEmailFor(StudentGroup group, String text) {
        dispatchEmails();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, atLeastOnce()).send(captor.capture());
        List<EmailMessage> matching = captor.getAllValues().stream()
                .filter(m -> m.subject().contains(group.getGroupCode()) && m.body().contains(text)).toList();
        assertThat(matching).as("email to %s containing %s", group.getGroupCode(), text).isNotEmpty();
        return matching.get(matching.size() - 1);
    }
}
