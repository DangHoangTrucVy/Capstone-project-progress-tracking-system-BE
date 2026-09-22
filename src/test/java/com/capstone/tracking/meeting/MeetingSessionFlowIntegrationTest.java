package com.capstone.tracking.meeting;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises MeetingSessionService: a session can only be started from a Confirmed booking, a
 * booking can spawn at most one session (DB unique FK backed), and the
 * Scheduled -> In Progress -> Concluded lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MeetingSessionFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User instructor;
    private String instructorToken;

    @BeforeEach
    void setUp() {
        instructor = save(User.builder().email("gv-meeting@fpt.edu.vn").fullName("GV Instructor")
                .passwordHash(passwordEncoder.encode("x")).role(Role.INSTRUCTOR).status(UserStatus.ACTIVE).build());
        instructorToken = token(instructor);
    }

    @Test
    void cannotCreateSessionForNonConfirmedBooking() throws Exception {
        StudentGroup group = group("MTG-A");
        String leaderToken = token(leaderFor(group));
        String bookingId = book(group, leaderToken, Instant.now().plus(3, ChronoUnit.DAYS));

        mockMvc.perform(delete("/api/v1/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "no longer needed"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/meetings")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotCreateTwoSessionsForSameBooking() throws Exception {
        StudentGroup group = group("MTG-B");
        String leaderToken = token(leaderFor(group));
        String bookingId = book(group, leaderToken, Instant.now().plus(3, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/meetings")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/meetings")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isConflict());
    }

    @Test
    void startEndLifecycleTransitions() throws Exception {
        StudentGroup group = group("MTG-C");
        String leaderToken = token(leaderFor(group));
        String bookingId = book(group, leaderToken, Instant.now().plus(3, ChronoUnit.DAYS));

        String sessionBody = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/meetings")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionStatus").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(sessionBody).get("id").asText();

        mockMvc.perform(put("/api/v1/meetings/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("IN_PROGRESS"));

        mockMvc.perform(put("/api/v1/meetings/" + sessionId + "/end")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rawNotes", "Discussed progress."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("CONCLUDED"))
                .andExpect(jsonPath("$.rawNotes").value("Discussed progress."));
    }

    // --- fixtures -------------------------------------------------------------------------------

    private String book(StudentGroup group, String leaderToken, Instant start) throws Exception {
        Map<String, Object> slotPayload = Map.of(
                "startTime", start.toString(),
                "endTime", start.plus(30, ChronoUnit.MINUTES).toString(),
                "durationMinutes", 30,
                "capacity", 2,
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

        String bookingBody = mockMvc.perform(post("/api/v1/slots/" + slotId + "/book")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupId", group.getId()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(bookingBody).get("id").asText();
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

    private User save(User user) {
        return userRepository.save(user);
    }

    private String token(User user) {
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
