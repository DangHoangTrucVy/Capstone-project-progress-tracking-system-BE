package com.capstone.tracking.question;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A Group Leader sends questions to their supervisor through their own topic's question bank.
 * Deliberately NOT @Transactional: a test transaction would keep the Hibernate session open and hide a
 * LazyInitializationException on QuestionResponse's createdBy, which real requests would hit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeaderSendsQuestionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private User supervisor;
    private User leader;
    private User student;
    private Topic ownTopic;
    private Topic otherTopic;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        supervisor = user("gv", Role.INSTRUCTOR);
        leader = user("leader", Role.GROUP_LEADER);
        student = user("student", Role.STUDENT);
        ownTopic = topic("OWN");
        otherTopic = topic("OTHER");
        group = studentGroupRepository.save(StudentGroup.builder().groupCode("Q-" + suffix).topic(ownTopic)
                .supervisor(supervisor).semester("Fall2026").status(GroupStatus.ACTIVE).build());
        member(leader, true);
        member(student, false);
    }

    @AfterEach
    void tearDown() {
        List<UUID> topics = List.of(ownTopic.getId(), otherTopic.getId());
        topics.forEach(id -> jdbcTemplate.update("delete from question_bank_items where topic_id = ?", id));
        jdbcTemplate.update("delete from group_members where group_id = ?", group.getId());
        jdbcTemplate.update("delete from student_groups where id = ?", group.getId());
        topics.forEach(id -> jdbcTemplate.update("delete from topics where id = ?", id));
        List.of(supervisor, leader, student).forEach(u -> jdbcTemplate.update("delete from users where id = ?", u.getId()));
    }

    @Test
    void leaderSendsQuestionForOwnTopicAndSupervisorSeesWhoSentIt() throws Exception {
        mockMvc.perform(post("/api/v1/topics/" + ownTopic.getId() + "/questions")
                        .header("Authorization", bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Architecture\",\"questionText\":\"Nên tách service chấm bài không?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdById").value(leader.getId().toString()));

        mockMvc.perform(get("/api/v1/topics/" + ownTopic.getId() + "/questions").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].createdByName").value("leader " + suffix))
                .andExpect(jsonPath("$.content[0].createdByRole").value("GROUP_LEADER"));
    }

    @Test
    void leaderCannotSendQuestionForAnotherGroupsTopic() throws Exception {
        mockMvc.perform(post("/api/v1/topics/" + otherTopic.getId() + "/questions")
                        .header("Authorization", bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionText\":\"?\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void plainStudentCannotSendQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/topics/" + ownTopic.getId() + "/questions")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionText\":\"?\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTopicIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/topics/" + UUID.randomUUID() + "/questions").header("Authorization", bearer(supervisor)))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedRequestsAreBadRequestInsteadOfServerError() throws Exception {
        // What a frontend sends for a group that has no topic yet.
        mockMvc.perform(get("/api/v1/topics/null/questions").header("Authorization", bearer(leader)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/topics/" + ownTopic.getId() + "/questions")
                        .header("Authorization", bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    private User user(String kind, Role role) {
        return userRepository.save(User.builder().email(kind + "-" + suffix + "@fpt.edu.vn").fullName(kind + " " + suffix)
                .passwordHash("x").role(role).status(UserStatus.ACTIVE).build());
    }

    private Topic topic(String kind) {
        return topicRepository.save(Topic.builder().topicCode(kind + "-" + suffix).title(kind + " topic")
                .admin(supervisor).status(TopicStatus.PUBLISHED).build());
    }

    private void member(User user, boolean isLeader) {
        groupMemberRepository.save(GroupMember.builder().group(group).user(user).isLeader(isLeader)
                .joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
