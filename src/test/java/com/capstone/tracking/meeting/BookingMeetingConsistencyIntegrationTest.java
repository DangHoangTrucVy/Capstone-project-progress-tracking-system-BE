package com.capstone.tracking.meeting;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.meeting.dto.EndSessionRequest;
import com.capstone.tracking.meeting.dto.MinuteGenerateRequest;
import com.capstone.tracking.meeting.dto.RequirementCreateRequest;
import com.capstone.tracking.scheduling.Booking;
import com.capstone.tracking.scheduling.BookingRepository;
import com.capstone.tracking.scheduling.BookingStatus;
import com.capstone.tracking.scheduling.LocationType;
import com.capstone.tracking.scheduling.ScheduleSlot;
import com.capstone.tracking.scheduling.ScheduleSlotRepository;
import com.capstone.tracking.scheduling.SlotStatus;
import com.capstone.tracking.scheduling.dto.CancelBookingRequest;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:booking_meeting_consistency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class BookingMeetingConsistencyIntegrationTest extends WorkflowTestSupport {

    @Autowired private BookingRepository bookings;
    @Autowired private ScheduleSlotRepository slots;
    @Autowired private MeetingSessionRepository sessions;
    @Autowired private MeetingSessionService sessionService;

    @MockBean private Clock clock;

    private User instructor;
    private User leaderA;
    private User leaderB;
    private StudentGroup groupA;
    private StudentGroup groupB;

    private ScheduleSlot slot;
    private Booking bookingA;
    private Instant slotStart;
    private Instant slotEnd;
    private Instant baseNow;

    @BeforeEach
    void setUp() {
        baseNow = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        when(clock.instant()).thenAnswer(inv -> baseNow);

        instructor = user("slot-inst", Role.INSTRUCTOR);
        leaderA = user("leader-a", Role.GROUP_LEADER);
        leaderB = user("leader-b", Role.GROUP_LEADER);

        groupA = group("GRP-A", instructor);
        addMember(groupA, leaderA, true, MemberStatus.ACTIVE);

        groupB = group("GRP-B", instructor);
        addMember(groupB, leaderB, true, MemberStatus.ACTIVE);

        // Slot starts 3 days in the future
        slotStart = baseNow.plus(3, ChronoUnit.DAYS);
        slotEnd = slotStart.plus(30, ChronoUnit.MINUTES);

        slot = slots.save(ScheduleSlot.builder()
                .instructor(instructor)
                .startTime(slotStart)
                .endTime(slotEnd)
                .durationMinutes(30)
                .capacityGroups(1)
                .bookedCount(1)
                .locationType(LocationType.ONLINE)
                .meetingUrl("https://meet.example.com/" + suffix)
                .status(SlotStatus.FULL)
                .build());

        bookingA = bookings.save(Booking.builder()
                .slot(slot)
                .group(groupA)
                .bookedAt(baseNow)
                .bookingStatus(BookingStatus.CONFIRMED)
                .build());
    }

    @Test
    @DisplayName("Cancelled booking cannot become ATTENDED via old meeting session (Issue #45)")
    void cancelledBookingCannotBecomeAttendedViaOldMeeting() throws Exception {
        // Step 1: Create session for Booking A
        MeetingSession session = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        // Step 2: Cancel Booking A (outside 2-hour window)
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Plans changed"))))
                .andExpect(status().isOk());

        // Verify booking A and session are both CANCELLED
        Booking refreshedBooking = bookings.findById(bookingA.getId()).orElseThrow();
        assertThat(refreshedBooking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        MeetingSession refreshedSession = sessions.findById(session.getId()).orElseThrow();
        assertThat(refreshedSession.getSessionStatus()).isEqualTo(SessionStatus.CANCELLED);

        // Step 3: Advance clock to slotStart and attempt to start old meeting session
        when(clock.instant()).thenReturn(slotStart);

        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isBadRequest());

        // Step 4: Advance clock to slotEnd and attempt to end old meeting session
        when(clock.instant()).thenReturn(slotEnd);

        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EndSessionRequest("fake notes"))))
                .andExpect(status().isBadRequest());

        // Booking A must remain CANCELLED, never revived to ATTENDED!
        Booking finalBooking = bookings.findById(bookingA.getId()).orElseThrow();
        assertThat(finalBooking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        MeetingSession finalSession = sessions.findById(session.getId()).orElseThrow();
        assertThat(finalSession.getSessionStatus()).isEqualTo(SessionStatus.CANCELLED);
    }

    @Test
    @DisplayName("Sequential lifecycle: A cancels, B re-books, A cannot hijack slot or corrupt B's attendance")
    void sequentialLifecycle_groupBRebooks_groupACannotCorrupt() throws Exception {
        // 1. Group A creates meeting session M_A
        MeetingSession sessionA = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        // 2. Group A cancels booking A
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel for B"))))
                .andExpect(status().isOk());

        // Slot capacity is restored
        ScheduleSlot refreshedSlot = slots.findById(slot.getId()).orElseThrow();
        assertThat(refreshedSlot.getBookedCount()).isEqualTo(0);
        assertThat(refreshedSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        // 3. Group B books slot S
        mockMvc.perform(post("/api/v1/slots/" + slot.getId() + "/book")
                        .header("Authorization", "Bearer " + token(leaderB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupB.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        refreshedSlot = slots.findById(slot.getId()).orElseThrow();
        assertThat(refreshedSlot.getBookedCount()).isEqualTo(1);
        assertThat(refreshedSlot.getStatus()).isEqualTo(SlotStatus.FULL);

        Booking bookingB = bookings.findAll().stream()
                .filter(b -> b.getGroup().getId().equals(groupB.getId()) && b.getBookingStatus() == BookingStatus.CONFIRMED)
                .findFirst().orElseThrow();

        // 4. Time arrives: Group A attempts start/end on old session M_A -> BLOCKED
        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + sessionA.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isBadRequest());

        when(clock.instant()).thenReturn(slotEnd);
        mockMvc.perform(put("/api/v1/meetings/" + sessionA.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isBadRequest());

        // Booking A remains CANCELLED; Booking B remains CONFIRMED; slot bookedCount remains 1
        assertThat(bookings.findById(bookingA.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookings.findById(bookingB.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);

        // 5. Group B creates session M_B, starts and ends legitimately
        MeetingSession sessionB = sessions.save(MeetingSession.builder()
                .booking(bookingB)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + sessionB.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leaderB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("IN_PROGRESS"));

        when(clock.instant()).thenReturn(slotEnd);
        mockMvc.perform(put("/api/v1/meetings/" + sessionB.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leaderB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EndSessionRequest("Session completed properly"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("CONCLUDED"));

        // Booking B is now ATTENDED; Booking A is still CANCELLED
        assertThat(bookings.findById(bookingB.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.ATTENDED);
        assertThat(bookings.findById(bookingA.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cannot cancel booking when meeting session is already IN_PROGRESS")
    void cannotCancelBookingWhenMeetingIsAlreadyInProgress() throws Exception {
        MeetingSession session = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        // Start session
        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isOk());

        // Attempt cancel while session is IN_PROGRESS
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel mid-meeting"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already in_progress")));

        assertThat(bookings.findById(bookingA.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Cannot cancel booking when meeting session is already CONCLUDED")
    void cannotCancelBookingWhenMeetingIsAlreadyConcluded() throws Exception {
        MeetingSession session = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        // Start and end session
        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isOk());

        when(clock.instant()).thenReturn(slotEnd);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EndSessionRequest("Done"))))
                .andExpect(status().isOk());

        // Attempt cancel after concluded (booking is now ATTENDED)
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel after end"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a Confirmed booking can be cancelled"));

        assertThat(bookings.findById(bookingA.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.ATTENDED);
    }

    @Test
    @DisplayName("Duplicate cancellation does not double restore capacity")
    void duplicateCancellationDoesNotDoubleRestoreCapacity() throws Exception {
        // First cancellation succeeds
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel once"))))
                .andExpect(status().isOk());

        ScheduleSlot refreshedSlot = slots.findById(slot.getId()).orElseThrow();
        assertThat(refreshedSlot.getBookedCount()).isEqualTo(0);

        // Second cancellation fails
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel again"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a Confirmed booking can be cancelled"));

        // Capacity is not decremented below 0
        refreshedSlot = slots.findById(slot.getId()).orElseThrow();
        assertThat(refreshedSlot.getBookedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Cannot create meeting session for a cancelled booking")
    void cannotCreateSessionForCancelledBooking() throws Exception {
        // Cancel booking
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel first"))))
                .andExpect(status().isOk());

        // Attempt to create session for cancelled booking
        mockMvc.perform(post("/api/v1/bookings/" + bookingA.getId() + "/meetings")
                        .header("Authorization", "Bearer " + token(leaderA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A meeting session can only be started from a Confirmed booking"));
    }

    @Test
    @DisplayName("Cannot generate minutes or add requirements for a cancelled meeting session")
    void cannotGenerateMinutesOrAddRequirementsForCancelledSession() throws Exception {
        MeetingSession session = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .rawNotes("Initial discussion notes")
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        // Cancel booking -> session becomes CANCELLED
        mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelBookingRequest("Cancel now"))))
                .andExpect(status().isOk());

        assertThat(sessions.findById(session.getId()).orElseThrow().getSessionStatus())
                .isEqualTo(SessionStatus.CANCELLED);

        // Attempt add requirement -> 400
        mockMvc.perform(post("/api/v1/meetings/" + session.getId() + "/requirements")
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RequirementCreateRequest("Req 1", "Desc", Priority.HIGH))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cancelled")));

        // Attempt generate minutes -> 400
        mockMvc.perform(post("/api/v1/meetings/" + session.getId() + "/minutes/generate")
                        .header("Authorization", "Bearer " + token(leaderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MinuteGenerateRequest(null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cancelled")));
    }

    @Test
    @DisplayName("Concurrent race: cancel vs start resolves cleanly without data corruption")
    void concurrentRace_cancelVsStart_resolvesCleanly() throws Exception {
        MeetingSession session = sessions.save(MeetingSession.builder()
                .booking(bookingA)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());

        when(clock.instant()).thenReturn(slotStart);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // Thread 1: cancel booking
        executor.submit(() -> {
            ready.countDown();
            try {
                startLatch.await();
                mockMvc.perform(delete("/api/v1/bookings/" + bookingA.getId())
                                .header("Authorization", "Bearer " + token(leaderA))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new CancelBookingRequest("Concurrent cancel"))))
                        .andDo(result -> {
                            int status = result.getResponse().getStatus();
                            if (status == 200) successCount.incrementAndGet();
                            else failCount.incrementAndGet();
                        });
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        });

        // Thread 2: start meeting session
        executor.submit(() -> {
            ready.countDown();
            try {
                startLatch.await();
                mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                                .header("Authorization", "Bearer " + token(leaderA)))
                        .andDo(result -> {
                            int status = result.getResponse().getStatus();
                            if (status == 200) successCount.incrementAndGet();
                            else failCount.incrementAndGet();
                        });
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Exactly one should succeed and one should fail (or both serialized consistently)
        Booking finalBooking = bookings.findById(bookingA.getId()).orElseThrow();
        MeetingSession finalSession = sessions.findById(session.getId()).orElseThrow();

        if (finalBooking.getBookingStatus() == BookingStatus.CANCELLED) {
            // Cancel won: session must be CANCELLED, start must not have made it IN_PROGRESS
            assertThat(finalSession.getSessionStatus()).isEqualTo(SessionStatus.CANCELLED);
        } else {
            // Start won: session is IN_PROGRESS, booking is CONFIRMED, cancel failed
            assertThat(finalBooking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(finalSession.getSessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        }
    }

    // --- Helpers -------------------------------------------------------------

    private void addMember(StudentGroup grp, User u, boolean isLeader, MemberStatus status) {
        groupMemberRepository.save(GroupMember.builder()
                .group(grp)
                .user(u)
                .isLeader(isLeader)
                .joinedAt(Instant.now())
                .status(status)
                .build());
    }

    private StudentGroup group(String code, User supervisor) {
        return studentGroupRepository.save(StudentGroup.builder()
                .groupCode(code + "-" + suffix)
                .supervisor(supervisor)
                .semester("Fall2026")
                .status(com.capstone.tracking.group.GroupStatus.ACTIVE)
                .build());
    }

    private String token(User user) {
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
