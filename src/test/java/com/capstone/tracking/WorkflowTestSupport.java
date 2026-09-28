package com.capstone.tracking;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.notification.email.EmailSender;
import com.capstone.tracking.security.JwtTokenProvider;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicRepository;
import com.capstone.tracking.topic.TopicStatus;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fixtures for the end-to-end workflow tests. Deliberately NOT @Transactional: domain events (notifications, emails)
 * are only relayed after a real commit. Every name carries a random suffix so tests sharing the H2 database never
 * collide. EmailSender is mocked so tests can check who was emailed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class WorkflowTestSupport {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected UserRepository userRepository;
    @Autowired protected TopicRepository topicRepository;
    @Autowired protected StudentGroupRepository studentGroupRepository;
    @Autowired protected GroupMemberRepository groupMemberRepository;
    @Autowired protected JwtTokenProvider jwtTokenProvider;
    @MockBean protected EmailSender emailSender;

    protected final String suffix = UUID.randomUUID().toString().substring(0, 8);

    protected User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "-" + suffix + "@fpt.edu.vn").fullName(name + " " + suffix)
                .passwordHash("x").role(role).status(UserStatus.ACTIVE).build());
    }

    protected StudentGroup group(String code, User supervisor, boolean withTopic) {
        Topic topic = withTopic ? topicRepository.save(Topic.builder().topicCode(code + "-T-" + suffix)
                .title("Topic of " + code).admin(supervisor).status(TopicStatus.PUBLISHED).build()) : null;
        return studentGroupRepository.save(StudentGroup.builder().groupCode(code + "-" + suffix).semester("WF-" + suffix)
                .supervisor(supervisor).topic(topic).status(withTopic ? GroupStatus.ACTIVE : GroupStatus.FORMED).build());
    }

    protected void join(StudentGroup group, User user, boolean leader) {
        groupMemberRepository.save(GroupMember.builder().group(group).user(user).isLeader(leader)
                .joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
    }

    protected String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }

    protected ResultActions postJson(String url, User as, Object body) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", bearer(as))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions getAs(String url, User as) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", bearer(as)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
