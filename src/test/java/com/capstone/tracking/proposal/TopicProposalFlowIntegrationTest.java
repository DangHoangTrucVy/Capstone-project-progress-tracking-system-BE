package com.capstone.tracking.proposal;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Giai đoạn 2: Leader submits 10 topics -> supervisor forwards one -> Council rejects/approves, max 4 rounds. */
class TopicProposalFlowIntegrationTest extends WorkflowTestSupport {

    private User admin;
    private User supervisor;
    private User otherInstructor;
    private User council;
    private User leader;
    private User member;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        admin = user("tp-admin", Role.ADMIN);
        supervisor = user("tp-gv", Role.INSTRUCTOR);
        otherInstructor = user("tp-gv2", Role.INSTRUCTOR);
        council = user("tp-hd", Role.COUNCIL);
        leader = user("tp-leader", Role.GROUP_LEADER);
        member = user("tp-member", Role.STUDENT);
        group = group("TP", supervisor, false);
        join(group, leader, true);
        join(group, member, false);
    }

    @Test
    void rejectedThenResubmittedInAdminOpenedRoundAndApproved() throws Exception {
        String base = "/api/v1/groups/" + group.getId() + "/topic-proposals";

        // Round 1 needs exactly 10 topics.
        postJson(base, leader, topics(9)).andExpect(status().isBadRequest());
        JsonNode round1 = body(postJson(base, leader, topics(10))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_INSTRUCTOR"))
                .andExpect(jsonPath("$.topics.length()").value(10)));
        String id1 = round1.get("id").asText();
        String pickedItem = round1.get("topics").get(3).get("id").asText();

        // Only one proposal under review at a time; the supervisor was notified.
        postJson(base, leader, topics(10)).andExpect(status().isConflict());
        getAs("/api/v1/notifications", supervisor)
                .andExpect(jsonPath("$.content[0].type").value("TOPIC_PROPOSAL_SUBMITTED"));

        // Only the group's own supervisor pre-reviews.
        postJson("/api/v1/topic-proposals/" + id1 + "/forward", otherInstructor, Map.of("itemId", pickedItem))
                .andExpect(status().isForbidden());
        JsonNode forwarded = body(postJson("/api/v1/topic-proposals/" + id1 + "/forward", supervisor,
                Map.of("itemId", pickedItem, "note", "Đề tài 4 khả thi nhất"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_COUNCIL"))
                .andExpect(jsonPath("$.selectedTitle").value("Topic 4")));
        assertThat(Instant.parse(forwarded.get("councilDeadline").asText()))
                .isCloseTo(Instant.now().plus(Duration.ofDays(14)), within(1, ChronoUnit.MINUTES));

        // Council: feedback is mandatory on rejection; Instructors cannot decide.
        postJson("/api/v1/topic-proposals/" + id1 + "/decision", supervisor, Map.of("decision", "APPROVED"))
                .andExpect(status().isForbidden());
        postJson("/api/v1/topic-proposals/" + id1 + "/decision", council, Map.of("decision", "REJECTED"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/topic-proposals/" + id1 + "/decision", council,
                Map.of("decision", "REJECTED", "feedback", "Phạm vi quá rộng"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.remainingRounds").value(3));

        // The rejection is emailed: To the leader, CC the member and the supervisor, with the feedback.
        EmailMessage rejection = lastEmailContaining("KHÔNG DUYỆT");
        assertThat(rejection.to()).containsExactly(leader.getEmail());
        assertThat(rejection.cc()).containsExactlyInAnyOrder(member.getEmail(), supervisor.getEmail());
        assertThat(rejection.body()).contains(group.getGroupCode()).contains("Phạm vi quá rộng");
        getAs("/api/v1/notifications?unreadOnly=true", member)
                .andExpect(jsonPath("$.content[0].type").value("TOPIC_REJECTED"))
                .andExpect(jsonPath("$.content[0].details").value(org.hamcrest.Matchers.containsString("Phạm vi quá rộng")));

        // Round 2 needs the Admin to open the window.
        postJson(base, leader, topics(1)).andExpect(status().isBadRequest());
        postJson("/api/v1/proposal-rounds", admin, Map.of("semester", group.getSemester(), "roundNumber", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openNow").value(true));
        JsonNode round2 = body(postJson(base, leader, topics(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.round").value(2)));
        String id2 = round2.get("id").asText();

        JsonNode forwarded2 = body(postJson("/api/v1/topic-proposals/" + id2 + "/forward", supervisor,
                Map.of("itemId", round2.get("topics").get(0).get("id").asText())).andExpect(status().isOk()));
        assertThat(Instant.parse(forwarded2.get("councilDeadline").asText()))
                .isCloseTo(Instant.now().plus(Duration.ofDays(10)), within(1, ChronoUnit.MINUTES));

        JsonNode approved = body(postJson("/api/v1/topic-proposals/" + id2 + "/decision", council,
                Map.of("decision", "APPROVED", "feedback", "Đạt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")));

        // The approved topic became the group's official topic.
        StudentGroup reloaded = studentGroupRepository.findById(group.getId()).orElseThrow();
        assertThat(reloaded.getTopic()).isNotNull();
        assertThat(reloaded.getTopic().getId().toString()).isEqualTo(approved.get("approvedTopicId").asText());
        assertThat(reloaded.getTopic().getTitle()).isEqualTo("Topic 1");
        assertThat(reloaded.getStatus()).isEqualTo(GroupStatus.ACTIVE);
        assertThat(lastEmailContaining("đã DUYỆT").to()).containsExactly(leader.getEmail());

        postJson(base, leader, topics(1)).andExpect(status().isConflict());
        getAs(base, member).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        getAs(base, user("tp-outsider", Role.STUDENT)).andExpect(status().isForbidden());
    }

    @Test
    void nonLeaderCannotSubmit() throws Exception {
        postJson("/api/v1/groups/" + group.getId() + "/topic-proposals", member, topics(10))
                .andExpect(status().isForbidden());
        User otherLeader = user("tp-other-leader", Role.GROUP_LEADER);
        postJson("/api/v1/groups/" + group.getId() + "/topic-proposals", otherLeader, topics(10))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> topics(int n) {
        List<Map<String, String>> list = IntStream.rangeClosed(1, n)
                .mapToObj(i -> Map.of("title", "Topic " + i, "description", "Mô tả " + i)).toList();
        return Map.of("topics", list);
    }

    private EmailMessage lastEmailContaining(String text) {
        dispatchEmails();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, atLeastOnce()).send(captor.capture());
        List<EmailMessage> matching = new ArrayList<>(captor.getAllValues().stream()
                .filter(m -> m.subject().contains(group.getGroupCode()) && m.body().contains(text)).toList());
        assertThat(matching).as("an email about %s containing %s", group.getGroupCode(), text).isNotEmpty();
        return matching.get(matching.size() - 1);
    }
}
