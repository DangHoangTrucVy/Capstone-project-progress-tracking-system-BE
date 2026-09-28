package com.capstone.tracking.warning;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.milestone.Milestone;
import com.capstone.tracking.milestone.MilestoneRepository;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bước 4.1 (progress bar) and 4.4 (warning flags as Overview badges, emailed to the group). */
class WarningFlagAndOverviewIntegrationTest extends WorkflowTestSupport {

    @Autowired private MilestoneRepository milestoneRepository;

    private User supervisor;
    private User leader;
    private User member;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        supervisor = user("wf-gv", Role.INSTRUCTOR);
        leader = user("wf-leader", Role.GROUP_LEADER);
        member = user("wf-member", Role.STUDENT);
        group = group("WF", supervisor, true);
        join(group, leader, true);
        join(group, member, false);
    }

    @Test
    void supervisorFlagsInactiveMemberAndLeaderSeesBadge() throws Exception {
        String url = "/api/v1/groups/" + group.getId() + "/warning-flags";
        postJson(url, user("wf-other-gv", Role.INSTRUCTOR),
                Map.of("type", "GROUP_BEHIND_SCHEDULE", "severity", "HIGH", "reason", "x")).andExpect(status().isForbidden());
        postJson(url, leader, Map.of("type", "GROUP_BEHIND_SCHEDULE", "severity", "HIGH", "reason", "x"))
                .andExpect(status().isForbidden());
        postJson(url, supervisor, Map.of("type", "MEMBER_INACTIVE", "severity", "MEDIUM", "reason", "x"))
                .andExpect(status().isBadRequest());

        String flagId = body(postJson(url, supervisor, Map.of("type", "MEMBER_INACTIVE", "severity", "MEDIUM",
                        "reason", "Vắng 3 buổi họp liên tiếp", "memberId", member.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.memberName").value(member.getFullName()))).get("id").asText();

        getAs("/api/v1/groups/" + group.getId() + "/overview", leader)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeWarningFlags.length()").value(1))
                .andExpect(jsonPath("$.activeWarningFlags[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.unreadNotifications").value(1));

        dispatchEmails();

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, atLeastOnce()).send(captor.capture());
        EmailMessage email = captor.getAllValues().stream()
                .filter(m -> m.subject().contains(group.getGroupCode())).reduce((a, b) -> b).orElseThrow();
        assertThat(email.to()).containsExactly(leader.getEmail());
        assertThat(email.cc()).contains(member.getEmail(), supervisor.getEmail());
        assertThat(email.body()).contains("Vắng 3 buổi họp liên tiếp");

        mockMvc.perform(put("/api/v1/warning-flags/" + flagId + "/resolve").header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Đã tham gia lại\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        getAs("/api/v1/groups/" + group.getId() + "/overview", leader)
                .andExpect(jsonPath("$.activeWarningFlags.length()").value(0));
        getAs("/api/v1/groups/" + group.getId() + "/overview", user("wf-outsider", Role.STUDENT))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificationStreamAcceptsTokenAsQueryParam() throws Exception {
        String token = bearer(leader).substring("Bearer ".length());
        mockMvc.perform(get("/api/v1/notifications/stream").param("access_token", token))
                .andExpect(request().asyncStarted());
        mockMvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void progressBarCountsSubmittedMilestones() throws Exception {
        List<Milestone> milestones = List.of(
                milestone("SRS", 1, Instant.now().plus(3, ChronoUnit.DAYS)),
                milestone("SDS", 2, Instant.now().minus(1, ChronoUnit.DAYS)),
                milestone("DEMO", 3, Instant.now().plus(30, ChronoUnit.DAYS)),
                milestone("FINAL", 4, null));

        mockMvc.perform(multipart("/api/v1/groups/" + group.getId() + "/documents")
                        .param("title", "SRS v1").param("url", "https://github.com/x")
                        .param("milestoneId", milestones.get(0).getId().toString())
                        .header("Authorization", bearer(leader)))
                .andExpect(status().isCreated());

        getAs("/api/v1/groups/" + group.getId() + "/overview", leader)
                .andExpect(jsonPath("$.milestoneProgress.total").value(4))
                .andExpect(jsonPath("$.milestoneProgress.submitted").value(1))
                .andExpect(jsonPath("$.milestoneProgress.percentage").value(25))
                .andExpect(jsonPath("$.milestoneProgress.milestones[1].overdue").value(true))
                .andExpect(jsonPath("$.topicTitle").value("Topic of WF"))
                .andExpect(jsonPath("$.defenseTrack").value("NOT_REVIEWED"));
    }

    private Milestone milestone(String code, int seq, Instant due) {
        return milestoneRepository.save(Milestone.builder().code(code).name(code).semester(group.getSemester())
                .sequenceNo(seq).dueDate(due).build());
    }
}
