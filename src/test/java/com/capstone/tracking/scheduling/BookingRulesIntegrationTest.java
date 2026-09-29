package com.capstone.tracking.scheduling;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bước 3.1 / 3.2: one group per slot, 24h notice, one slot per day, finish the last meeting before booking again. */
class BookingRulesIntegrationTest extends WorkflowTestSupport {

    @MockBean private Clock clock;

    private User instructor;
    private User leader;
    private User member;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(inv -> Instant.now());
        instructor = user("bk-gv", Role.INSTRUCTOR);
        leader = user("bk-leader", Role.GROUP_LEADER);
        member = user("bk-member", Role.STUDENT);
        group = group("BK", instructor, true);
        join(group, leader, true);
        join(group, member, false);
    }

    @Test
    void slotServesExactlyOneGroup() throws Exception {
        postJson("/api/v1/slots", instructor, slotPayload(Instant.now().plus(3, ChronoUnit.DAYS), 2))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mustBookAtLeast24HoursAhead() throws Exception {
        String slot = createSlot(Instant.now().plus(5, ChronoUnit.HOURS));
        book(slot, leader).andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheGroupsLeaderBooks() throws Exception {
        String slot = createSlot(Instant.now().plus(3, ChronoUnit.DAYS));
        book(slot, member).andExpect(status().isForbidden());
        book(slot, user("bk-other-leader", Role.GROUP_LEADER)).andExpect(status().isForbidden());
    }

    @Test
    void finishPreviousMeetingAndAtMostOneSlotPerDay() throws Exception {
        // Distinct days per test run so the shared instructor calendar never overlaps.
        LocalDate day = LocalDate.now(VnTime.ZONE).plusDays(10 + Math.abs(suffix.hashCode() % 300));
        String morning = createSlot(at(day, 9));
        String afternoon = createSlot(at(day, 15));
        String nextDay = createSlot(at(day.plusDays(1), 9));

        String bookingId = body(book(morning, leader).andExpect(status().isOk())).get("id").asText();

        // Previous meeting not held yet -> cannot book another.
        book(nextDay, leader).andExpect(status().isBadRequest());

        // Hold the meeting: create session, start, end -> booking ATTENDED.
        String sessionId = body(postNoBody("/api/v1/bookings/" + bookingId + "/meetings", leader)
                .andExpect(status().isCreated())).get("id").asText();
        when(clock.instant()).thenReturn(at(day, 9));
        mockMvc.perform(put("/api/v1/meetings/" + sessionId + "/start").header("Authorization", bearer(leader)))
                .andExpect(status().isOk());
        when(clock.instant()).thenReturn(at(day, 9).plus(30, ChronoUnit.MINUTES));
        mockMvc.perform(put("/api/v1/meetings/" + sessionId + "/end").header("Authorization", bearer(leader)))
                .andExpect(status().isOk());
        when(clock.instant()).thenAnswer(inv -> Instant.now());

        // Same day again -> at most one slot per day.
        book(afternoon, leader).andExpect(status().isConflict());
        book(nextDay, leader).andExpect(status().isOk());
    }

    private Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(VnTime.ZONE).toInstant();
    }

    private String createSlot(Instant start) throws Exception {
        return body(postJson("/api/v1/slots", instructor, slotPayload(start, 1)).andExpect(status().isCreated()))
                .get("id").asText();
    }

    private Map<String, Object> slotPayload(Instant start, int capacity) {
        return Map.of("startTime", start.toString(), "endTime", start.plus(30, ChronoUnit.MINUTES).toString(),
                "durationMinutes", 30, "capacity", capacity, "locationType", "ONLINE", "meetingUrl", "https://meet.x/y");
    }

    private ResultActions book(String slotId, User as) throws Exception {
        return postJson("/api/v1/slots/" + slotId + "/book", as, Map.of("groupId", group.getId()));
    }

    private ResultActions postNoBody(String url, User as) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", bearer(as)));
    }
}
