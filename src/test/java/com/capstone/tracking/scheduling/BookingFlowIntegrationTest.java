package com.capstone.tracking.scheduling;

import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicRepository;
import com.capstone.tracking.topic.TopicStatus;
import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.group.GroupStatus;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises BookingService's locking/validation rules end to end through the HTTP layer:
 * capacity enforcement (NFR-002 / R-001), one-active-booking-per-group, and the late-cancellation window.
 * A true multi-thread race test belongs in a k6/JMeter script per NFR-002's own measurement column —
 * this proves the *rules* are correct, which a load test alone would not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookingFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private ScheduleSlotRepository scheduleSlotRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User instructor;
    private String instructorToken;

    @BeforeEach
    void setUp() {
        instructor = save(User.builder().email("gv1@fpt.edu.vn").fullName("GV Instructor")
                .passwordHash(passwordEncoder.encode("x")).role(Role.INSTRUCTOR).status(UserStatus.ACTIVE).build());
        instructorToken = token(instructor);
    }

    @Test
    void secondBookingOnLastSeatIsRejectedWithConflict() throws Exception {
        String slotId = createSlot(Instant.now().plus(3, ChronoUnit.DAYS), 1);

        StudentGroup groupA = group("GRP-A");
        StudentGroup groupB = group("GRP-B");
        String leaderAToken = token(leaderFor(groupA));
        String leaderBToken = token(leaderFor(groupB));

        mockMvc.perform(post("/api/v1/slots/" + slotId + "/book")
                        .header("Authorization", "Bearer " + leaderAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupA.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Same slot, different group, no seats left -> 409, not a silently-accepted double booking.
        mockMvc.perform(post("/api/v1/slots/" + slotId + "/book")
                        .header("Authorization", "Bearer " + leaderBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupB.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void groupCannotHoldTwoActiveBookings() throws Exception {
        String slot1 = createSlot(Instant.now().plus(2, ChronoUnit.DAYS), 1);
        String slot2 = createSlot(Instant.now().plus(4, ChronoUnit.DAYS), 1);
        StudentGroup group = group("GRP-C");
        String leaderToken = token(leaderFor(group));

        mockMvc.perform(post("/api/v1/slots/" + slot1 + "/book")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/slots/" + slot2 + "/book")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancellingWithinTwoHoursOfStartIsRejected() throws Exception {
        // Booked the required 24h ahead, then the slot is moved to start in 30 minutes.
        String slotId = createSlot(Instant.now().plus(3, ChronoUnit.DAYS), 1);
        StudentGroup group = group("GRP-D");
        String leaderToken = token(leaderFor(group));

        String body = mockMvc.perform(post("/api/v1/slots/" + slotId + "/book")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(body).get("id").asText();
        moveSlot(slotId, Instant.now().plus(30, ChronoUnit.MINUTES));

        mockMvc.perform(delete("/api/v1/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "test"))))
                .andExpect(status().isBadRequest());
    }

    // --- fixtures -------------------------------------------------------------------------------

    private String createSlot(Instant start, int capacity) throws Exception {
        Map<String, Object> payload = Map.of(
                "startTime", start.toString(),
                "endTime", start.plus(30, ChronoUnit.MINUTES).toString(),
                "durationMinutes", 30,
                "capacity", capacity,
                "locationType", "ONLINE",
                "meetingUrl", "https://meet.example.com/x"
        );
        String body = mockMvc.perform(post("/api/v1/slots")
                        .header("Authorization", "Bearer " + instructorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private StudentGroup group(String code) {
        Topic topic = topicRepository.save(Topic.builder()
                .topicCode(code + "-TOPIC").title("Topic for " + code).admin(instructor).status(TopicStatus.PUBLISHED).build());
        return studentGroupRepository.save(StudentGroup.builder()
                .groupCode(code).topic(topic).supervisor(instructor).semester("Fall2026").status(GroupStatus.ACTIVE).build());
    }

    private User leaderFor(StudentGroup group) {
        User leader = save(User.builder().email(group.getGroupCode().toLowerCase() + "@fpt.edu.vn")
                .fullName("Leader " + group.getGroupCode()).passwordHash(passwordEncoder.encode("x"))
                .role(Role.GROUP_LEADER).status(UserStatus.ACTIVE).build());
        groupMemberRepository.save(GroupMember.builder()
                .group(group).user(leader).isLeader(true).joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
        return leader;
    }

    private void moveSlot(String slotId, Instant start) {
        ScheduleSlot slot = scheduleSlotRepository.findById(UUID.fromString(slotId)).orElseThrow();
        slot.setStartTime(start);
        slot.setEndTime(start.plus(30, ChronoUnit.MINUTES));
        scheduleSlotRepository.saveAndFlush(slot);
    }

    private User save(User user) {
        return userRepository.save(user);
    }

    private String token(User user) {
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
