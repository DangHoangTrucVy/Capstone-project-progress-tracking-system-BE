package com.capstone.tracking.meeting;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.scheduling.Booking;
import com.capstone.tracking.scheduling.BookingRepository;
import com.capstone.tracking.scheduling.BookingStatus;
import com.capstone.tracking.scheduling.LocationType;
import com.capstone.tracking.scheduling.ScheduleSlot;
import com.capstone.tracking.scheduling.ScheduleSlotRepository;
import com.capstone.tracking.scheduling.SlotStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:meeting_time_guard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class MeetingTimeGuardIntegrationTest extends WorkflowTestSupport {

    @Autowired private BookingRepository bookings;
    @Autowired private ScheduleSlotRepository slots;
    @Autowired private MeetingSessionRepository sessions;
    @Autowired private MeetingSessionService sessionService;

    @MockBean private Clock clock;

    private User instructor;
    private User outsiderInstructor;
    private User leader;
    private StudentGroup group;

    private ScheduleSlot slot;
    private Booking booking;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(inv -> Instant.now());

        instructor = user("slot-gv", Role.INSTRUCTOR);
        outsiderInstructor = user("out-gv", Role.INSTRUCTOR);
        leader = user("leader-tg", Role.GROUP_LEADER);
        group = group("GRP-TG", instructor);
        addMember(group, leader, true, MemberStatus.ACTIVE);

        slotStart = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
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

        booking = bookings.save(Booking.builder()
                .slot(slot)
                .group(group)
                .bookedAt(Instant.now())
                .bookingStatus(BookingStatus.CONFIRMED)
                .build());
    }

    @Test
    @DisplayName("Meeting start: Cannot start before slot startTime (returns 400, remains SCHEDULED)")
    void cannotStartBeforeSlotStartTime() throws Exception {
        MeetingSession session = createSession();

        // 1 second before scheduled start time
        when(clock.instant()).thenReturn(slotStart.minusSeconds(1));

        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot start meeting before its scheduled start time")));

        MeetingSession refreshed = sessions.findById(session.getId()).orElseThrow();
        assertThat(refreshed.getSessionStatus()).isEqualTo(SessionStatus.SCHEDULED);
        assertThat(refreshed.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("Meeting start: Can start at exact slot startTime (returns 200, status IN_PROGRESS)")
    void canStartAtExactSlotStartTime() throws Exception {
        MeetingSession session = createSession();

        when(clock.instant()).thenReturn(slotStart);

        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").value(slotStart.toString()));

        MeetingSession refreshed = sessions.findById(session.getId()).orElseThrow();
        assertThat(refreshed.getSessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(refreshed.getStartedAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(slotStart.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("Meeting start: Can start after slot startTime (during or after slot)")
    void canStartAfterSlotStartTime() throws Exception {
        MeetingSession session = createSession();

        when(clock.instant()).thenReturn(slotStart.plus(15, ChronoUnit.MINUTES));

        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("Meeting end: Cannot end a session that is still SCHEDULED (end before start)")
    void cannotEndBeforeStart() throws Exception {
        MeetingSession session = createSession();

        when(clock.instant()).thenReturn(slotEnd);

        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rawNotes", "notes"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only an In Progress session can be ended"));

        Booking refreshedBooking = bookings.findById(booking.getId()).orElseThrow();
        assertThat(refreshedBooking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Meeting end: Cannot conclude before slot endTime (returns 400, remains IN_PROGRESS and CONFIRMED)")
    void cannotEndBeforeSlotEndTime() throws Exception {
        MeetingSession session = createSession();

        // Start at start time
        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk());

        // Try to end 1 second before scheduled end time
        when(clock.instant()).thenReturn(slotEnd.minusSeconds(1));
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rawNotes", "Early attempt"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot conclude meeting before its scheduled end time")));

        MeetingSession refreshedSession = sessions.findById(session.getId()).orElseThrow();
        assertThat(refreshedSession.getSessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(refreshedSession.getEndedAt()).isNull();

        Booking refreshedBooking = bookings.findById(booking.getId()).orElseThrow();
        assertThat(refreshedBooking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Meeting end: Can conclude at exact slot endTime (transitions to CONCLUDED and booking ATTENDED)")
    void canEndAtExactSlotEndTime() throws Exception {
        MeetingSession session = createSession();

        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk());

        when(clock.instant()).thenReturn(slotEnd);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rawNotes", "Concluded discussion"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("CONCLUDED"))
                .andExpect(jsonPath("$.endedAt").value(slotEnd.toString()))
                .andExpect(jsonPath("$.rawNotes").value("Concluded discussion"));

        MeetingSession refreshedSession = sessions.findById(session.getId()).orElseThrow();
        assertThat(refreshedSession.getSessionStatus()).isEqualTo(SessionStatus.CONCLUDED);

        Booking refreshedBooking = bookings.findById(booking.getId()).orElseThrow();
        assertThat(refreshedBooking.getBookingStatus()).isEqualTo(BookingStatus.ATTENDED);
    }

    @Test
    @DisplayName("Meeting end: Can conclude after slot endTime")
    void canEndAfterSlotEndTime() throws Exception {
        MeetingSession session = createSession();

        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk());

        when(clock.instant()).thenReturn(slotEnd.plus(10, ChronoUnit.MINUTES));
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("CONCLUDED"));

        Booking refreshedBooking = bookings.findById(booking.getId()).orElseThrow();
        assertThat(refreshedBooking.getBookingStatus()).isEqualTo(BookingStatus.ATTENDED);
    }

    @Test
    @DisplayName("Exploit prevention: Future meeting cannot be ended immediately to unlock booking new slots")
    void exploitChainBlocked_thenLegitimateFlowUnlocksNewBooking() throws Exception {
        // Step 1: Group has booked S1 (3 days in future). Create session M1.
        MeetingSession session1 = createSession();

        // Step 2: Attempt immediate start & end (exploiting time guard absence)
        when(clock.instant()).thenAnswer(inv -> Instant.now());

        mockMvc.perform(put("/api/v1/meetings/" + session1.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/meetings/" + session1.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isBadRequest());

        // Booking 1 remains CONFIRMED
        assertThat(bookings.findById(booking.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.CONFIRMED);

        // Step 3: Attempt to book S2 (4 days in future) -> BLOCKED because S1 is not ATTENDED
        ScheduleSlot slot2 = slots.save(ScheduleSlot.builder()
                .instructor(instructor)
                .startTime(slotStart.plus(1, ChronoUnit.DAYS))
                .endTime(slotStart.plus(1, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES))
                .durationMinutes(30)
                .capacityGroups(1)
                .bookedCount(0)
                .locationType(LocationType.ONLINE)
                .meetingUrl("https://meet.example.com/slot2-" + suffix)
                .status(SlotStatus.AVAILABLE)
                .build());

        mockMvc.perform(post("/api/v1/slots/" + slot2.getId() + "/book")
                        .header("Authorization", "Bearer " + token(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already has an active booking")));

        // Step 4: Time arrives for S1 -> start and end legitimately
        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session1.getId() + "/start")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk());

        when(clock.instant()).thenReturn(slotEnd);
        mockMvc.perform(put("/api/v1/meetings/" + session1.getId() + "/end")
                        .header("Authorization", "Bearer " + token(leader)))
                .andExpect(status().isOk());

        // Booking 1 is now ATTENDED
        assertThat(bookings.findById(booking.getId()).orElseThrow().getBookingStatus())
                .isEqualTo(BookingStatus.ATTENDED);

        // Step 5: Now booking S2 succeeds!
        when(clock.instant()).thenAnswer(inv -> Instant.now());
        mockMvc.perform(post("/api/v1/slots/" + slot2.getId() + "/book")
                        .header("Authorization", "Bearer " + token(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("Authorization check precedes time check: outsider cannot start/end even at valid time")
    void outsiderCannotStartOrEndEvenAtValidTime() throws Exception {
        MeetingSession session = createSession();

        when(clock.instant()).thenReturn(slotStart);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/start")
                        .header("Authorization", "Bearer " + token(outsiderInstructor)))
                .andExpect(status().isForbidden());

        when(clock.instant()).thenReturn(slotEnd);
        mockMvc.perform(put("/api/v1/meetings/" + session.getId() + "/end")
                        .header("Authorization", "Bearer " + token(outsiderInstructor)))
                .andExpect(status().isForbidden());
    }

    // --- Helpers -------------------------------------------------------------

    private MeetingSession createSession() {
        return sessions.save(MeetingSession.builder()
                .booking(booking)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build());
    }

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
