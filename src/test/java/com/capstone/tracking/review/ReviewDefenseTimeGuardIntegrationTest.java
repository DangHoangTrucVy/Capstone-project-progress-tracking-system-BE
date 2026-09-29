package com.capstone.tracking.review;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.defense.DefenseSessionRepository;
import com.capstone.tracking.defense.DefenseStatus;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.semester.SemesterCalendar;
import com.capstone.tracking.semester.SemesterCalendarRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:review_defense_time_guard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ReviewDefenseTimeGuardIntegrationTest extends WorkflowTestSupport {

    @Autowired private ReviewSessionRepository reviewSessions;
    @Autowired private DefenseSessionRepository defenseSessions;
    @Autowired private SemesterCalendarRepository calendars;

    @MockBean private Clock clock;

    private User admin;
    private User supervisor;
    private User r1;
    private User r2;
    private User r3;
    private User leader;
    private StudentGroup group;

    private Instant baseTime;
    private Instant review1Time;
    private Instant review3Time;
    private String room;

    @BeforeEach
    void setUp() {
        baseTime = Instant.now().plus(100, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        when(clock.instant()).thenAnswer(inv -> baseTime);

        admin = user("time-admin", Role.ADMIN);
        supervisor = user("time-sup", Role.INSTRUCTOR);
        r1 = user("time-r1", Role.INSTRUCTOR);
        r2 = user("time-r2", Role.COUNCIL);
        r3 = user("time-r3", Role.COUNCIL);
        leader = user("time-leader", Role.GROUP_LEADER);

        group = group("GRP-TIME", supervisor, true);
        join(group, leader, true);

        room = "ROOM-" + suffix;
        calendars.save(new SemesterCalendar(group.getSemester(),
                baseTime.atZone(com.capstone.tracking.common.VnTime.ZONE).toLocalDate().minusDays(14)));

        review1Time = baseTime;
        review3Time = baseTime.plus(Duration.ofDays(77));
    }

    @Test
    @DisplayName("Review result: cannot record before scheduled start time (returns 400, remains uncompleted)")
    void cannotRecordReviewResultBeforeScheduledStartTime() throws Exception {
        // Schedule Review 1 at review1Time
        String reviewId = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_1", review1Time, List.of(r1, r2), null))
                .andExpect(status().isCreated())).get("id").asText();

        // Time is 1 second before review1Time
        when(clock.instant()).thenReturn(review1Time.minusSeconds(1));

        postJson("/api/v1/reviews/" + reviewId + "/result", r1, Map.of("feedback", "Early feedback attempt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot record review result before the session's scheduled start time")));

        ReviewSession refreshed = reviewSessions.findById(UUID.fromString(reviewId)).orElseThrow();
        assertThat(refreshed.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("Review result: can record at exact scheduled start time")
    void canRecordReviewResultAtExactScheduledStartTime() throws Exception {
        String reviewId = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_1", review1Time, List.of(r1, r2), null))
                .andExpect(status().isCreated())).get("id").asText();

        when(clock.instant()).thenReturn(review1Time);

        postJson("/api/v1/reviews/" + reviewId + "/result", r1, Map.of("feedback", "On-time review feedback"))
                .andExpect(status().isOk());

        ReviewSession refreshed = reviewSessions.findById(UUID.fromString(reviewId)).orElseThrow();
        assertThat(refreshed.getCompletedAt()).isEqualTo(review1Time);
        assertThat(refreshed.getFeedback()).isEqualTo("On-time review feedback");
    }

    @Test
    @DisplayName("Review result: cannot record duplicate result (returns 409 Conflict)")
    void cannotRecordDuplicateReviewResult() throws Exception {
        String reviewId = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_1", review1Time, List.of(r1, r2), null))
                .andExpect(status().isCreated())).get("id").asText();

        when(clock.instant()).thenReturn(review1Time);

        postJson("/api/v1/reviews/" + reviewId + "/result", r1, Map.of("feedback", "First result"))
                .andExpect(status().isOk());

        postJson("/api/v1/reviews/" + reviewId + "/result", r1, Map.of("feedback", "Second result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The result of this review is already recorded"));
    }

    @Test
    @DisplayName("Defense result: cannot record before scheduled start time (returns 400, remains SCHEDULED)")
    void cannotRecordDefenseResultBeforeScheduledStartTime() throws Exception {
        // Complete Review 3 at review3Time
        String r3Id = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_3", review3Time, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        when(clock.instant()).thenReturn(review3Time);
        postJson("/api/v1/reviews/" + r3Id + "/result", r2,
                Map.of("feedback", "Ready", "outcome", "READY_FOR_DEFENSE_1"))
                .andExpect(status().isOk());

        // Schedule Defense 1 at defenseTime (14 days after Review 3)
        Instant defenseTime = review3Time.plus(Duration.ofDays(14));
        String defenseId = body(postJson("/api/v1/defenses", admin,
                defensePayload(group, 1, defenseTime, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        // Attempt to record defense result 1 second before defenseTime
        when(clock.instant()).thenReturn(defenseTime.minusSeconds(1));

        postJson("/api/v1/defenses/" + defenseId + "/result", r2,
                Map.of("passed", true, "score", 9.0, "feedback", "Early attempt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot record defense result before the session's scheduled start time")));

        var refreshedDefense = defenseSessions.findById(UUID.fromString(defenseId)).orElseThrow();
        assertThat(refreshedDefense.getStatus()).isEqualTo(DefenseStatus.SCHEDULED);
        assertThat(refreshedDefense.getGradedAt()).isNull();

        var refreshedGroup = studentGroupRepository.findById(group.getId()).orElseThrow();
        assertThat(refreshedGroup.getStatus()).isEqualTo(GroupStatus.ACTIVE);
    }

    @Test
    @DisplayName("Defense result: can record at exact scheduled start time and transitions group to COMPLETED")
    void canRecordDefenseResultAtExactScheduledStartTime() throws Exception {
        // Complete Review 3
        String r3Id = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_3", review3Time, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        when(clock.instant()).thenReturn(review3Time);
        postJson("/api/v1/reviews/" + r3Id + "/result", r2,
                Map.of("feedback", "Ready", "outcome", "READY_FOR_DEFENSE_1"))
                .andExpect(status().isOk());

        // Schedule Defense 1
        Instant defenseTime = review3Time.plus(Duration.ofDays(14));
        String defenseId = body(postJson("/api/v1/defenses", admin,
                defensePayload(group, 1, defenseTime, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        // Record at exact defense start time
        when(clock.instant()).thenReturn(defenseTime);

        postJson("/api/v1/defenses/" + defenseId + "/result", r2,
                Map.of("passed", true, "score", 9.0, "feedback", "Excellent presentation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));

        var refreshedDefense = defenseSessions.findById(UUID.fromString(defenseId)).orElseThrow();
        assertThat(refreshedDefense.getStatus()).isEqualTo(DefenseStatus.PASSED);
        assertThat(refreshedDefense.getScore()).isEqualTo(9.0);
        assertThat(refreshedDefense.getGradedAt()).isEqualTo(defenseTime);

        var refreshedGroup = studentGroupRepository.findById(group.getId()).orElseThrow();
        assertThat(refreshedGroup.getStatus()).isEqualTo(GroupStatus.COMPLETED);
    }

    @Test
    @DisplayName("Defense result: duplicate recording blocked (returns 409 Conflict)")
    void cannotRecordDuplicateDefenseResult() throws Exception {
        // Complete Review 3
        String r3Id = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_3", review3Time, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        when(clock.instant()).thenReturn(review3Time);
        postJson("/api/v1/reviews/" + r3Id + "/result", r2,
                Map.of("feedback", "Ready", "outcome", "READY_FOR_DEFENSE_1"))
                .andExpect(status().isOk());

        Instant defenseTime = review3Time.plus(Duration.ofDays(14));
        String defenseId = body(postJson("/api/v1/defenses", admin,
                defensePayload(group, 1, defenseTime, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        when(clock.instant()).thenReturn(defenseTime);

        postJson("/api/v1/defenses/" + defenseId + "/result", r2,
                Map.of("passed", true, "score", 9.0))
                .andExpect(status().isOk());

        postJson("/api/v1/defenses/" + defenseId + "/result", r2,
                Map.of("passed", true, "score", 9.5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The result of this defense is already recorded"));
    }

    @Test
    @DisplayName("Exploit prevention: cannot record Review 3 early to fast-track defense and completion")
    void exploitPrevention_cannotFastTrackReview3AndDefense() throws Exception {
        // Schedule Review 3 in week 14 (far future)
        String r3Id = body(postJson("/api/v1/reviews", admin,
                reviewPayload(group, "REVIEW_3", review3Time, List.of(r1, r2, r3), r2))
                .andExpect(status().isCreated())).get("id").asText();

        // 1. Chair attempts to record outcome immediately (at baseTime, well before week 14) -> BLOCKED
        when(clock.instant()).thenReturn(baseTime);
        postJson("/api/v1/reviews/" + r3Id + "/result", r2,
                Map.of("feedback", "Premature", "outcome", "READY_FOR_DEFENSE_1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("before the session's scheduled start time")));

        // 2. Admin cannot schedule Defense 1 because Review 3 outcome does not exist yet -> BLOCKED
        Instant defenseTime = review3Time.plus(Duration.ofDays(14));
        postJson("/api/v1/defenses", admin, defensePayload(group, 1, defenseTime, List.of(r1, r2, r3), r2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("has no closed council (Review 3) result yet")));

        // Group status remains ACTIVE
        assertThat(studentGroupRepository.findById(group.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupStatus.ACTIVE);
    }

    // --- Helpers -------------------------------------------------------------

    private Map<String, Object> reviewPayload(StudentGroup grp, String round, Instant at, List<User> panel, User chair) {
        Map<String, Object> m = new HashMap<>(Map.of(
                "groupId", grp.getId(),
                "round", round,
                "scheduledAt", at.toString(),
                "durationMinutes", 45,
                "location", "R-" + suffix,
                "reviewerIds", panel.stream().map(User::getId).toList()));
        if (chair != null) {
            m.put("chairId", chair.getId());
        }
        return m;
    }

    private Map<String, Object> defensePayload(StudentGroup grp, int attempt, Instant at, List<User> committee, User chair) {
        Map<String, Object> m = new HashMap<>(Map.of(
                "groupId", grp.getId(),
                "attempt", attempt,
                "scheduledAt", at.toString(),
                "durationMinutes", 60,
                "room", room,
                "committeeIds", committee.stream().map(User::getId).toList()));
        if (chair != null) {
            m.put("chairId", chair.getId());
        }
        return m;
    }
}
