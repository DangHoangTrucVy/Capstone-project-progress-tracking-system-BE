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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises RequirementLogService (Sprint 4 — API-007): create defaults to Open, update applies status/assignee. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequirementLogFlowIntegrationTest {

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
        instructor = save(User.builder().email("gv-req@fpt.edu.vn").fullName("GV Instructor")
                .passwordHash(passwordEncoder.encode("x")).role(Role.INSTRUCTOR).status(UserStatus.ACTIVE).build());
        instructorToken = token(instructor);
    }

    @Test
    void createDefaultsToOpenStatus() throws Exception {
        StudentGroup group = group("REQ-A");
        String leaderToken = token(leaderFor(group));
        String sessionId = createSession(group, leaderToken);

        mockMvc.perform(post("/api/v1/meetings/" + sessionId + "/requirements")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Add pagination", "description", "Needed for the listing screen", "priority", "HIGH"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void updateChangesStatusAndAssignee() throws Exception {
        StudentGroup group = group("REQ-B");
        User leader = leaderFor(group);
        String leaderToken = token(leader);
        String sessionId = createSession(group, leaderToken);

        String body = mockMvc.perform(post("/api/v1/meetings/" + sessionId + "/requirements")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Fix login bug", "priority", "MEDIUM"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reqId = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(put("/api/v1/requirements/" + reqId)
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "RESOLVED", "assignedTo", leader.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.assignedTo").value(leader.getId().toString()));
    }

    // --- fixtures -------------------------------------------------------------------------------

    private String createSession(StudentGroup group, String leaderToken) throws Exception {
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
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
        String bookingId = objectMapper.readTree(bookingBody).get("id").asText();

        String sessionBody = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/meetings")
                        .header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(sessionBody).get("id").asText();
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
