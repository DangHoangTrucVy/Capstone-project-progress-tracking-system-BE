package com.capstone.tracking.report;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.scheduling.ScheduleSlot;
import com.capstone.tracking.scheduling.ScheduleSlotRepository;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicRepository;
import com.capstone.tracking.topic.TopicStatus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises ReportService (Sprint 5 — API-011): one Concluded session out of two counted for the
 * current ISO week gives attendanceRate 0.5, and open/closed requirement counts split correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportSummaryIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private ScheduleSlotRepository scheduleSlotRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockBean private Clock clock;

    private User instructor;
    private String instructorToken;
    private User admin;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(inv -> Instant.now());
        instructor = save(User.builder().email("gv-report@fpt.edu.vn").fullName("GV Instructor")
                .passwordHash(passwordEncoder.encode("x")).role(Role.INSTRUCTOR).status(UserStatus.ACTIVE).build());
        instructorToken = token(instructor);
        admin = save(User.builder().email("admin-report@fpt.edu.vn").fullName("Admin")
                .passwordHash(passwordEncoder.encode("x")).role(Role.ADMIN).status(UserStatus.ACTIVE).build());
    }

    @Test
    void nonAdminCannotViewSummary() throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary?semester=Fall2026Report")
                        .header("Authorization", "Bearer " + instructorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void summaryAggregatesSessionsAndRequirements() throws Exception {
        String semester = "Fall2026Report";
        Instant now = Instant.now();
        int currentWeek = isoWeek(now);
        // Every timestamp below must fall in the current ISO week, whatever day the test runs on:
        // a few hours back, or forward when the week has only just started.
        Instant start1 = isoWeek(now.minus(3, ChronoUnit.HOURS)) == currentWeek
                ? now.minus(3, ChronoUnit.HOURS) : now.plus(1, ChronoUnit.HOURS);

        // Two groups in the same semester — a group may hold only one active booking at a time,
        // so the two sessions being compared need separate groups.
        StudentGroup groupA = group("RPT-A", semester);
        String leaderTokenA = token(leaderFor(groupA));
        StudentGroup groupB = group("RPT-B", semester);
        String leaderTokenB = token(leaderFor(groupB));
        String leaderToken = leaderTokenA;

        // Session 1: fully concluded within the current week.
        Instant end1 = start1.plus(30, ChronoUnit.MINUTES);
        String bookingId1 = book(groupA, leaderTokenA, start1);
        String sessionId1 = createSession(bookingId1, leaderTokenA);
        when(clock.instant()).thenReturn(start1);
        mockMvc.perform(put("/api/v1/meetings/" + sessionId1 + "/start").header("Authorization", "Bearer " + leaderTokenA))
                .andExpect(status().isOk());
        when(clock.instant()).thenReturn(end1);
        mockMvc.perform(put("/api/v1/meetings/" + sessionId1 + "/end").header("Authorization", "Bearer " + leaderTokenA))
                .andExpect(status().isOk());
        when(clock.instant()).thenAnswer(inv -> Instant.now());

        // Session 2: booked in the same week but never started -> counts toward the
        // denominator (falls back to the slot's own start time) but not sessionsHeld.
        String bookingId2 = book(groupB, leaderTokenB, end1.plus(30, ChronoUnit.MINUTES));
        createSession(bookingId2, leaderTokenB);

        // One requirement left Open, one moved to Resolved.
        String openReqBody = mockMvc.perform(post("/api/v1/meetings/" + sessionId1 + "/requirements")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Still open", "priority", "LOW"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        objectMapper.readTree(openReqBody).get("id").asText();

        String closedReqBody = mockMvc.perform(post("/api/v1/meetings/" + sessionId1 + "/requirements")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Fix this", "priority", "HIGH"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String closedReqId = objectMapper.readTree(closedReqBody).get("id").asText();
        mockMvc.perform(put("/api/v1/requirements/" + closedReqId)
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "RESOLVED"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reports/summary?semester=" + semester + "&weekNumber=" + currentWeek)
                        .header("Authorization", "Bearer " + token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionsHeld").value(1))
                .andExpect(jsonPath("$.attendanceRate").value(0.5))
                .andExpect(jsonPath("$.openReqs").value(1))
                .andExpect(jsonPath("$.closedReqs").value(1));
    }

    // --- fixtures -------------------------------------------------------------------------------

    private String book(StudentGroup group, String leaderToken, Instant requested) throws Exception {
        // Slots must be created in the future and booked 24h ahead: create and book it 2 days later,
        // then move it to the requested time.
        Instant created = requested.plus(2, ChronoUnit.DAYS);
        Map<String, Object> slotPayload = Map.of(
                "startTime", created.toString(),
                "endTime", created.plus(30, ChronoUnit.MINUTES).toString(),
                "durationMinutes", 30,
                "capacity", 1,
                "locationType", "ONLINE",
                "meetingUrl", "https://meet.example.com/x"
        );
        String slotBody = mockMvc.perform(post("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(slotPayload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String slotId = objectMapper.readTree(slotBody).get("id").asText();
        ScheduleSlot slot = scheduleSlotRepository.findById(UUID.fromString(slotId)).orElseThrow();

        String bookingBody = mockMvc.perform(post("/api/v1/slots/" + slotId + "/book")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        slot.setStartTime(requested);
        slot.setEndTime(requested.plus(30, ChronoUnit.MINUTES));
        scheduleSlotRepository.saveAndFlush(slot);
        return objectMapper.readTree(bookingBody).get("id").asText();
    }

    private String createSession(String bookingId, String leaderToken) throws Exception {
        String sessionBody = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/meetings")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(sessionBody).get("id").asText();
    }

    private StudentGroup group(String code, String semester) {
        Topic topic = topicRepository.save(Topic.builder()
                .topicCode(code + "-TOPIC").title("Topic for " + code).admin(instructor).status(TopicStatus.PUBLISHED).build());
        return studentGroupRepository.save(StudentGroup.builder()
                .groupCode(code).topic(topic).supervisor(instructor).semester(semester).status(GroupStatus.ACTIVE).build());
    }

    private User leaderFor(StudentGroup group) {
        User leader = save(User.builder().email(group.getGroupCode().toLowerCase() + "@fpt.edu.vn")
                .fullName("Leader " + group.getGroupCode()).passwordHash(passwordEncoder.encode("x"))
                .role(Role.GROUP_LEADER).status(UserStatus.ACTIVE).build());
        groupMemberRepository.save(GroupMember.builder()
                .group(group).user(leader).isLeader(true).joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
        return leader;
    }

    private static int isoWeek(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    private User save(User user) {
        return userRepository.save(user);
    }

    private String token(User user) {
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
