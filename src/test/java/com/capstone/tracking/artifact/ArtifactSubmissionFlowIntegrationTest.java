package com.capstone.tracking.artifact;

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
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the resubmission/versioning rule, the group-membership guard, and the Instructor
 * accept transition for ArtifactSubmissionService (Sprint 3 — API-005).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ArtifactSubmissionFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User instructor;

    @BeforeEach
    void setUp() {
        instructor = save(User.builder().email("gv-artifact@fpt.edu.vn").fullName("GV Instructor")
                .passwordHash(passwordEncoder.encode("x")).role(Role.INSTRUCTOR).status(UserStatus.ACTIVE).build());
    }

    @Test
    void resubmittingSameTitleSupersedesPreviousVersion() throws Exception {
        StudentGroup group = group("ART-A");
        String leaderToken = token(leaderFor(group));

        String firstBody = submit(group.getId(), leaderToken, "Progress Report", "http://files/v1.pdf");
        String firstId = objectMapper.readTree(firstBody).get("id").asText();
        objectMapper.readTree(firstBody).get("version").asInt(); // v1

        String secondBody = submit(group.getId(), leaderToken, "Progress Report", "http://files/v2.pdf");
        assertVersion(secondBody, 2);

        mockMvc.perform(get("/api/v1/artifacts/" + firstId).header("Authorization", "Bearer " + leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERCEDED"));
    }

    @Test
    void nonMemberCannotSubmitArtifact() throws Exception {
        StudentGroup group = group("ART-B");
        User outsider = save(User.builder().email("outsider-art@fpt.edu.vn").fullName("Outsider")
                .passwordHash(passwordEncoder.encode("x")).role(Role.STUDENT).status(UserStatus.ACTIVE).build());

        mockMvc.perform(post("/api/v1/groups/" + group.getId() + "/artifacts")
                        .header("Authorization", "Bearer " + token(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Report", "fileUrl", "http://files/x.pdf"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void instructorAcceptTransitionsToAccepted() throws Exception {
        StudentGroup group = group("ART-C");
        String leaderToken = token(leaderFor(group));

        String body = submit(group.getId(), leaderToken, "Final Report", "http://files/final.pdf");
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(post("/api/v1/artifacts/" + id + "/accept")
                        .header("Authorization", "Bearer " + token(instructor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    // --- fixtures -------------------------------------------------------------------------------

    private String submit(java.util.UUID groupId, String leaderToken, String title, String fileUrl) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups/" + groupId + "/artifacts")
                        .header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "fileUrl", fileUrl))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body;
    }

    private void assertVersion(String body, int expected) throws Exception {
        int actual = objectMapper.readTree(body).get("version").asInt();
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
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
