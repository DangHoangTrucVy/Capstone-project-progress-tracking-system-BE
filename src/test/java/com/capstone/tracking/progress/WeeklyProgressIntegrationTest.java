package com.capstone.tracking.progress;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Weekly progress reports: one per group and week, tasks replaced on update, feedback by the supervisor only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WeeklyProgressIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User supervisor;
    private User otherInstructor;
    private User leader;
    private User member;
    private User outsider;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        supervisor = user("pg-gv", Role.INSTRUCTOR);
        otherInstructor = user("pg-gv2", Role.INSTRUCTOR);
        leader = user("pg-leader", Role.GROUP_LEADER);
        member = user("pg-member", Role.STUDENT);
        outsider = user("pg-outsider", Role.STUDENT);
        group = studentGroupRepository.save(StudentGroup.builder().groupCode("PG-G1").semester("Fall2026")
                .supervisor(supervisor).status(GroupStatus.ACTIVE).build());
        join(leader, true);
        join(member, false);
    }

    @Test
    void groupReportsWeeksAndDashboardShowsTheTrend() throws Exception {
        String week1 = create(leader, """
                {"weekNumber":1,"progressPercentage":20,"summary":"Setup","tasks":[
                  {"title":"Repo","status":"DONE","assigneeId":"%s"},
                  {"title":"SRS","status":"IN_PROGRESS","assigneeId":"%s"}]}
                """.formatted(leader.getId(), member.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalTasks").value(2))
                .andExpect(jsonPath("$.doneTasks").value(1))
                .andExpect(jsonPath("$.tasks[1].assigneeName").value("pg-member"))
                .andExpect(jsonPath("$.submittedByName").value("pg-leader"))
                .andReturn().getResponse().getContentAsString();

        create(member, "{\"weekNumber\":2,\"progressPercentage\":45,\"tasks\":[]}").andExpect(status().isCreated());

        // Same week twice -> 409, must update instead.
        create(member, "{\"weekNumber\":1,\"progressPercentage\":30}").andExpect(status().isConflict());

        // Update replaces the task list.
        String id = objectMapper.readTree(week1).get("id").asText();
        mockMvc.perform(put("/api/v1/progress/" + id).header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekNumber\":1,\"progressPercentage\":25,\"tasks\":[{\"title\":\"SRS\",\"status\":\"DONE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(25))
                .andExpect(jsonPath("$.totalTasks").value(1))
                .andExpect(jsonPath("$.doneTasks").value(1));

        mockMvc.perform(get("/api/v1/groups/" + group.getId() + "/progress").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].weekNumber").value(1))
                .andExpect(jsonPath("$[1].weekNumber").value(2));

        mockMvc.perform(get("/api/v1/groups/" + group.getId() + "/progress/summary").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportedWeeks").value(2))
                .andExpect(jsonPath("$.latestWeek").value(2))
                .andExpect(jsonPath("$.latestProgressPercentage").value(45))
                .andExpect(jsonPath("$.trend[0].progressPercentage").value(25));
    }

    @Test
    void onlyTheSupervisorOrAdminGivesFeedback() throws Exception {
        String body = create(leader, "{\"weekNumber\":1,\"progressPercentage\":10}")
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        String feedback = "{\"feedback\":\"Cần tăng tốc\"}";

        mockMvc.perform(put("/api/v1/progress/" + id + "/feedback").header("Authorization", bearer(otherInstructor))
                        .contentType(MediaType.APPLICATION_JSON).content(feedback))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/progress/" + id + "/feedback").header("Authorization", bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON).content(feedback))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/progress/" + id + "/feedback").header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON).content(feedback))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructorFeedback").value("Cần tăng tốc"))
                .andExpect(jsonPath("$.feedbackByName").value("pg-gv"));
    }

    @Test
    void invalidReportsAndOutsidersAreRejected() throws Exception {
        create(leader, "{\"weekNumber\":1,\"progressPercentage\":120}").andExpect(status().isBadRequest());
        create(leader, "{\"weekNumber\":16,\"progressPercentage\":10}").andExpect(status().isBadRequest());
        create(leader, "{\"weekNumber\":1,\"progressPercentage\":10,\"tasks\":[{\"title\":\"x\",\"status\":\"DONE\",\"assigneeId\":\""
                + outsider.getId() + "\"}]}").andExpect(status().isBadRequest());
        create(leader, "{\"weekNumber\":1,\"progressPercentage\":10,\"tasks\":[{\"title\":\"x\",\"status\":\"FINISHED\"}]}")
                .andExpect(status().isBadRequest());

        create(outsider, "{\"weekNumber\":1,\"progressPercentage\":10}").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/groups/" + group.getId() + "/progress").header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());
    }

    private ResultActions create(User user, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + group.getId() + "/progress")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private void join(User user, boolean isLeader) {
        groupMemberRepository.save(GroupMember.builder().group(group).user(user).isLeader(isLeader)
                .joinedAt(Instant.now()).status(MemberStatus.ACTIVE).build());
    }

    private User user(String name, Role role) {
        return userRepository.save(User.builder().email(name + "@fpt.edu.vn").fullName(name).passwordHash("x")
                .role(role).status(UserStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    }
}
