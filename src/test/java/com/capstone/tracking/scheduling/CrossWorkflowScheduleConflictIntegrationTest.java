package com.capstone.tracking.scheduling;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.defense.dto.DefenseScheduleRequest;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.review.ClosedCouncilOutcome;
import com.capstone.tracking.review.ReviewRound;
import com.capstone.tracking.review.ReviewSession;
import com.capstone.tracking.review.ReviewSessionRepository;
import com.capstone.tracking.review.dto.ReviewCloneRequest;
import com.capstone.tracking.review.dto.ReviewScheduleRequest;
import com.capstone.tracking.scheduling.dto.BookRequest;
import com.capstone.tracking.scheduling.dto.CancelBookingRequest;
import com.capstone.tracking.scheduling.dto.SlotCreateRequest;
import com.capstone.tracking.semester.SemesterCalendar;
import com.capstone.tracking.semester.SemesterCalendarRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #47: Cross-workflow schedule conflict prevention between consultation bookings,
 * review sessions, and defense sessions.
 */
class CrossWorkflowScheduleConflictIntegrationTest extends WorkflowTestSupport {

    @Autowired private SemesterCalendarRepository calendars;
    @Autowired private ReviewSessionRepository reviewSessions;
    @Autowired private BookingRepository bookingRepository;
    @MockBean protected Clock clock;

    private Instant simulatedNow;
    private User admin;
    private User instructorA;
    private User instructorB;
    private User instructorC;
    private User leaderX;
    private User leaderY;
    private StudentGroup groupX;
    private StudentGroup groupY;
    private Instant base;

    @BeforeEach
    void setUp() {
        simulatedNow = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        when(clock.instant()).thenAnswer(inv -> simulatedNow);

        admin = user("cw-admin", Role.ADMIN);
        instructorA = user("cw-gv-a", Role.INSTRUCTOR);
        instructorB = user("cw-gv-b", Role.INSTRUCTOR);
        instructorC = user("cw-gv-c", Role.INSTRUCTOR);

        leaderX = user("cw-ldr-x", Role.GROUP_LEADER);
        leaderY = user("cw-ldr-y", Role.GROUP_LEADER);

        groupX = group("CWX", instructorA, true);
        groupY = group("CWY", instructorB, true);

        join(groupX, leaderX, true);
        join(groupX, user("cw-mbr-x", Role.STUDENT), false);
        join(groupY, leaderY, true);
        join(groupY, user("cw-mbr-y", Role.STUDENT), false);

        // Schedule far in the future within semester review week 3
        base = simulatedNow.plus(300 + Math.abs(suffix.hashCode() % 1000), ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        calendars.save(new SemesterCalendar(groupX.getSemester(), base.atZone(VnTime.ZONE).toLocalDate().minusDays(14)));
        calendars.save(new SemesterCalendar(groupY.getSemester(), base.atZone(VnTime.ZONE).toLocalDate().minusDays(14)));
    }

    private String createSlot(User instructor, Instant start, int durationMinutes) throws Exception {
        SlotCreateRequest req = new SlotCreateRequest(
                start, start.plus(Duration.ofMinutes(durationMinutes)),
                durationMinutes, 1, LocationType.ONLINE, "https://meet.google.com/" + suffix);
        JsonNode node = body(postJson("/api/v1/slots", instructor, req).andExpect(status().isCreated()));
        return node.get("id").asText();
    }

    private String bookSlot(String slotId, StudentGroup group, User leader) throws Exception {
        JsonNode node = body(postJson("/api/v1/slots/" + slotId + "/book", leader,
                new BookRequest(group.getId(), "Consultation notes")).andExpect(status().isOk()));
        return node.get("id").asText();
    }

    private ReviewScheduleRequest reviewReq(StudentGroup group, Instant start, int durationMinutes,
                                           List<User> reviewers, User chair) {
        return new ReviewScheduleRequest(group.getId(), ReviewRound.REVIEW_1, start, durationMinutes,
                "Room-" + suffix, reviewers.stream().map(User::getId).toList(),
                chair != null ? chair.getId() : null);
    }

    @Test
    void sameInstructor_differentGroup_reviewBlockedByConsultationBooking() throws Exception {
        // Instructor A has consultation slot booked by Group X
        String slotId = createSlot(instructorA, base, 60);
        bookSlot(slotId, groupX, leaderX);

        // Admin schedules Review 1 for Group Y with Instructor A in panel at the same time
        ReviewScheduleRequest req = reviewReq(groupY, base, 60, List.of(instructorA, instructorB), null);
        postJson("/api/v1/reviews", admin, req)
                .andExpect(status().isConflict());
    }

    @Test
    void sameInstructor_differentGroup_bookingBlockedByReview() throws Exception {
        // Admin schedules Review 1 for Group Y with Instructor A in panel
        ReviewScheduleRequest req = reviewReq(groupY, base, 60, List.of(instructorA, instructorB), null);
        postJson("/api/v1/reviews", admin, req).andExpect(status().isCreated());

        // Instructor A previously had an open slot at that time
        // When Group X tries to book Instructor A's slot, it must be rejected
        String slotId = createSlot(instructorA, base.plus(Duration.ofDays(1)), 60);
        // Note: slot at the exact same time as review cannot be created by Instructor A:
        SlotCreateRequest conflictSlotReq = new SlotCreateRequest(
                base, base.plus(Duration.ofMinutes(60)), 60, 1, LocationType.ONLINE, "https://meet.google.com/x");
        postJson("/api/v1/slots", instructorA, conflictSlotReq)
                .andExpect(status().isConflict());
    }

    @Test
    void sameGroup_differentInstructor_reviewBlockedByConsultationBooking() throws Exception {
        // Group X has consultation booking with Instructor A
        String slotId = createSlot(instructorA, base, 60);
        bookSlot(slotId, groupX, leaderX);

        // Admin schedules Review 1 for Group X with Instructor B and Instructor C (neither is Instructor A)
        ReviewScheduleRequest req = reviewReq(groupX, base, 60, List.of(instructorB, instructorC), null);
        postJson("/api/v1/reviews", admin, req)
                .andExpect(status().isConflict());
    }

    @Test
    void sameGroup_differentInstructor_bookingBlockedByReview() throws Exception {
        // Admin schedules Review 1 for Group X with Instructor B and Instructor C
        ReviewScheduleRequest req = reviewReq(groupX, base, 60, List.of(instructorB, instructorC), null);
        postJson("/api/v1/reviews", admin, req).andExpect(status().isCreated());

        // Instructor A (free) creates a slot at that same time
        String slotId = createSlot(instructorA, base, 60);

        // Group X tries to book Instructor A's slot -> blocked due to group's review
        postJson("/api/v1/slots/" + slotId + "/book", leaderX, new BookRequest(groupX.getId(), "Consultation"))
                .andExpect(status().isConflict());
    }

    @Test
    void differentInstructorAndGroup_differentRoom_allowed() throws Exception {
        // Group X books Instructor A at base
        String slotId = createSlot(instructorA, base, 60);
        bookSlot(slotId, groupX, leaderX);

        // Group Y has Review 1 with Instructor B and C at base in Room-CW
        ReviewScheduleRequest req = new ReviewScheduleRequest(groupY.getId(), ReviewRound.REVIEW_1, base, 60,
                "Room-CW-" + suffix, List.of(instructorB.getId(), instructorC.getId()), null);
        postJson("/api/v1/reviews", admin, req)
                .andExpect(status().isCreated());
    }

    @Test
    void contiguousSchedules_allowed() throws Exception {
        // Group X books Instructor A from base to base + 60m
        String slotId = createSlot(instructorA, base, 60);
        bookSlot(slotId, groupX, leaderX);

        // Review 1 for Group X with Instructor A immediately after: base + 60m to base + 120m
        ReviewScheduleRequest req = new ReviewScheduleRequest(groupX.getId(), ReviewRound.REVIEW_1,
                base.plus(Duration.ofMinutes(60)), 60, "Room-Contig-" + suffix,
                List.of(instructorA.getId(), instructorB.getId()), null);
        postJson("/api/v1/reviews", admin, req)
                .andExpect(status().isCreated());
    }

    @Test
    void cancelledBooking_doesNotBlockReviewScheduling() throws Exception {
        // Group X books Instructor A from base to base + 60m
        String slotId = createSlot(instructorA, base, 60);
        String bookingId = bookSlot(slotId, groupX, leaderX);

        // Cancel the booking
        mockMvc.perform(delete("/api/v1/bookings/" + bookingId)
                .header("Authorization", bearer(leaderX))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel for review"))))
                .andExpect(status().isOk());

        // Now Admin can schedule Review 1 for Group X with Instructor A at the same time
        ReviewScheduleRequest req = reviewReq(groupX, base, 60, List.of(instructorA, instructorB), null);
        postJson("/api/v1/reviews", admin, req)
                .andExpect(status().isCreated());
    }

    @Test
    void unbookedSlot_doesNotBlockReview_butBlocksBookingLater() throws Exception {
        // Instructor A publishes an unbooked slot at base
        String slotId = createSlot(instructorA, base, 60);

        // Admin schedules Review 1 for Group Y with Instructor A in panel at base
        // Unbooked slot policy: Admin review scheduling is allowed
        ReviewScheduleRequest req = reviewReq(groupY, base, 60, List.of(instructorA, instructorB), null);
        postJson("/api/v1/reviews", admin, req)
                .andExpect(status().isCreated());

        // Later, Group X tries to book Instructor A's unbooked slot -> blocked because Instructor A has a review!
        postJson("/api/v1/slots/" + slotId + "/book", leaderX, new BookRequest(groupX.getId(), "Consultation"))
                .andExpect(status().isConflict());

        // Also Instructor A cannot create another slot during this review window
        SlotCreateRequest anotherSlot = new SlotCreateRequest(
                base, base.plus(Duration.ofMinutes(60)), 60, 1, LocationType.ONLINE, "https://meet.google.com/y");
        postJson("/api/v1/slots", instructorA, anotherSlot)
                .andExpect(status().isConflict());
    }

    @Test
    void defenseAndConsultation_bidirectionalConflict() throws Exception {
        // Setup Review 3 outcome for Group X to clear Defense 1 eligibility
        ReviewSession r3 = reviewSessions.save(ReviewSession.builder()
                .group(groupX)
                .round(ReviewRound.REVIEW_3)
                .scheduledAt(base.minus(Duration.ofDays(7)))
                .durationMinutes(60)
                .location("Room-R3")
                .outcome(ClosedCouncilOutcome.READY_FOR_DEFENSE_1)
                .completedAt(base.minus(Duration.ofDays(7)))
                .build());

        // Defense time at base + 3 days
        Instant defenseTime = base.plus(Duration.ofDays(3));

        // Group Y books Instructor A for consultation at defenseTime
        String slotId = createSlot(instructorA, defenseTime, 60);
        bookSlot(slotId, groupY, leaderY);

        // Admin tries to schedule Defense 1 for Group X with Instructor A in committee -> Conflict!
        DefenseScheduleRequest defReq = new DefenseScheduleRequest(
                groupX.getId(), 1, defenseTime, 60, "Room-Def-" + suffix,
                List.of(instructorA.getId(), instructorB.getId()), instructorA.getId());
        postJson("/api/v1/defenses", admin, defReq)
                .andExpect(status().isConflict());

        // Now schedule Defense 1 at defenseTime + 4 hours (when Instructor A is free)
        Instant freeDefenseTime = defenseTime.plus(Duration.ofHours(4));
        DefenseScheduleRequest defReqFree = new DefenseScheduleRequest(
                groupX.getId(), 1, freeDefenseTime, 60, "Room-Def-Free-" + suffix,
                List.of(instructorA.getId(), instructorB.getId()), instructorA.getId());
        postJson("/api/v1/defenses", admin, defReqFree)
                .andExpect(status().isCreated());

        // Group X tries to book a consultation slot at freeDefenseTime -> Conflict!
        String slot2 = createSlot(instructorC, freeDefenseTime, 60);
        postJson("/api/v1/slots/" + slot2 + "/book", leaderX, new BookRequest(groupX.getId(), "Consultation"))
                .andExpect(status().isConflict());
    }

    @Test
    void cloneRound_rollsBackEntireBatch_onMidBatchConflict() throws Exception {
        // Schedule Review 1 for Group X at base
        ReviewScheduleRequest reqX = new ReviewScheduleRequest(groupX.getId(), ReviewRound.REVIEW_1, base, 60,
                "Room-RX-" + suffix, List.of(instructorA.getId(), instructorB.getId()), null);
        postJson("/api/v1/reviews", admin, reqX).andExpect(status().isCreated());

        // Schedule Review 1 for Group Y at base + 2 hours
        ReviewScheduleRequest reqY = new ReviewScheduleRequest(groupY.getId(), ReviewRound.REVIEW_1, base.plus(Duration.ofHours(2)), 60,
                "Room-RY-" + suffix, List.of(instructorA.getId(), instructorB.getId()), null);
        postJson("/api/v1/reviews", admin, reqY).andExpect(status().isCreated());

        // Group Y has a consultation booking 28 days later (which conflicts with cloned Review 2 for Group Y)
        Instant cloneTimeY = base.plus(Duration.ofDays(28)).plus(Duration.ofHours(2));
        String slotY = createSlot(instructorC, cloneTimeY, 60);
        bookSlot(slotY, groupY, leaderY);

        // Attempt clone from Review 1 to Review 2 with offsetDays = 28
        // Group X would succeed, but Group Y will conflict -> entire clone batch must roll back!
        ReviewCloneRequest cloneReq = new ReviewCloneRequest(groupX.getSemester(), ReviewRound.REVIEW_1, ReviewRound.REVIEW_2, 28);
        postJson("/api/v1/reviews/clone", admin, cloneReq)
                .andExpect(status().isConflict());

        // Verify neither Group X nor Group Y has Review 2 saved
        assertThat(reviewSessions.existsByGroupIdAndRound(groupX.getId(), ReviewRound.REVIEW_2)).isFalse();
        assertThat(reviewSessions.existsByGroupIdAndRound(groupY.getId(), ReviewRound.REVIEW_2)).isFalse();
    }

    @Test
    void concurrentReviewAndBooking_serializedByScheduleGuard_onlyOneCommits() throws Exception {
        // Group X has an open slot created by Instructor A at base + 7 days
        Instant targetTime = base.plus(Duration.ofDays(7));
        String slotId = createSlot(instructorA, targetTime, 60);

        // Prepare 2 concurrent actions for targetTime:
        // 1: Admin schedules Review 1 for Group X with Instructor B and C
        // 2: Group X leader books Instructor A's slot
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Callable<Void> reviewTask = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            ReviewScheduleRequest req = new ReviewScheduleRequest(
                    groupX.getId(), ReviewRound.REVIEW_1, targetTime, 60,
                    "Room-Race-" + suffix, List.of(instructorB.getId(), instructorC.getId()), null);
            int code = postJson("/api/v1/reviews", admin, req).andReturn().getResponse().getStatus();
            if (code == 201) successCount.incrementAndGet();
            else if (code == 409) conflictCount.incrementAndGet();
            return null;
        };

        Callable<Void> bookTask = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            int code = postJson("/api/v1/slots/" + slotId + "/book", leaderX,
                    new BookRequest(groupX.getId(), "Race booking")).andReturn().getResponse().getStatus();
            if (code == 200) successCount.incrementAndGet();
            else if (code == 409) conflictCount.incrementAndGet();
            return null;
        };

        Future<Void> f1 = executor.submit(reviewTask);
        Future<Void> f2 = executor.submit(bookTask);

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one should succeed, and exactly one should conflict!
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
    }
}
