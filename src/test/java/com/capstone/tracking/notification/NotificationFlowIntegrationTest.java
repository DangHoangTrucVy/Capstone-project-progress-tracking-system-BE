package com.capstone.tracking.notification;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.security.JwtTokenProvider;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Domain events become in-app notifications (default in-process sink). Deliberately NOT @Transactional: events are
 * only relayed after a real commit, which a rolled-back test transaction never does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private User supervisor;
    private User leader;
    private User member;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        supervisor = user("nt-gv", Role.INSTRUCTOR);
        leader = user("nt-leader", Role.GROUP_LEADER);
        member = user("nt-member", Role.STUDENT);
        group = studentGroupRepository.save(StudentGroup.builder().groupCode("NT-" + suffix).semester("NotifTest")
                .supervisor(supervisor).status(GroupStatus.ACTIVE).build());
        join(leader, true);
        join(member, false);
    }

    @Test
    void supervisorIsNotifiedOfSubmissionsAndMembersOfFeedback() throws Exception {
        mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .param("title", "SRS").param("url", "https://github.com/x")
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("DOCUMENT_SUBMITTED"))
                .andExpect(jsonPath("$.content[0].message", containsString("SRS")))
                .andExpect(jsonPath("$.content[0].read").value(false));
        // The submitter is not told about their own action.
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(leader)))
                .andExpect(jsonPath("$.unread").value(0));

        String report = mockMvc.perform(post("/api/v1/groups/" + group.getId() + "/progress")
                        .header("Authorization", bearer(member)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekNumber\":1,\"progressPercentage\":20}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reportId = objectMapper.readTree(report).get("id").asText();

        mockMvc.perform(put("/api/v1/progress/" + reportId + "/feedback").header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":\"Tốt\"}"))
                .andExpect(status().isOk());

        // Both members hear about the feedback; the supervisor now has 2 unread (document + progress).
        for (User u : new User[]{leader, member}) {
            mockMvc.perform(get("/api/v1/notifications?unreadOnly=true").header("Authorization", bearer(u)))
                    .andExpect(jsonPath("$.content[0].type").value("PROGRESS_FEEDBACK"));
        }
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(supervisor)))
                .andExpect(jsonPath("$.unread").value(2));
    }

    @Test
    void notificationsCanBeMarkedReadOnlyByTheirRecipient() throws Exception {
        mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .param("title", "Demo").param("url", "https://demo.x").header("Authorization", bearer(member)))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(supervisor)))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("content").get(0).get("id").asText();

        mockMvc.perform(put("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(leader)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/notifications/" + id + "/read").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(put("/api/v1/notifications/read-all").header("Authorization", bearer(leader)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(leader)))
                .andExpect(jsonPath("$.unread").value(0));
    }

    private void join(User user, boolean isLeader) {
        groupMemberRepository.save(GroupMember.builder().group(group).user(user).isLeader(isLeader)
                .joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
    }

    private User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "-" + suffix + "@fpt.edu.vn").fullName(name)
                .passwordHash("x").role(role).status(UserStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
