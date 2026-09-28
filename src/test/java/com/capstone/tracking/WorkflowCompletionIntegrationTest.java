package com.capstone.tracking;

import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.proposal.TopicProposalRepository;
import com.capstone.tracking.review.*;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the new contract through HTTP, including competing transactions and a 40-group defense schedule. */
class WorkflowCompletionIntegrationTest extends WorkflowTestSupport {
    @Autowired TopicProposalRepository proposals;
    @Autowired ReviewSessionRepository reviews;

    @Test
    void onlyLeaderSubmitsAndSupervisorFeedbackReachesLeaderAndEmailCc() throws Exception {
        User supervisor = user("feedback-supervisor", Role.INSTRUCTOR);
        User leader = user("feedback-leader", Role.GROUP_LEADER);
        User member = user("feedback-member", Role.STUDENT);
        User other = user("other-supervisor", Role.INSTRUCTOR);
        StudentGroup group = group("FEEDBACK", supervisor, true);
        join(group, leader, true);
        join(group, member, false);
        String url = "/api/v1/groups/" + group.getId() + "/documents";
        Map<String,Object> document = Map.of("title", "SRS", "fileUrl", "https://example.com/srs");
        postJson(url, member, document).andExpect(status().isForbidden());
        postJson(url, user("outside-leader", Role.GROUP_LEADER), document).andExpect(status().isForbidden());
        String id = body(postJson(url, leader, document).andExpect(status().isCreated())).get("id").asText();
        String feedbackUrl = "/api/v1/documents/" + id + "/feedback";
        putAs(feedbackUrl, other, Map.of("feedback", "x", "accepted", true)).andExpect(status().isForbidden());
        putAs(feedbackUrl, supervisor, Map.of("feedback", "Bổ sung biểu đồ", "accepted", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feedback").value("Bổ sung biểu đồ"))
                .andExpect(jsonPath("$.reviewedById").value(supervisor.getId().toString()));
        getAs("/api/v1/notifications", leader).andExpect(jsonPath("$.content[0].type").value("DOCUMENT_FEEDBACK"));
        dispatchEmails();
        ArgumentCaptor<EmailMessage> emails = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, atLeastOnce()).send(emails.capture());
        EmailMessage email = emails.getAllValues().stream().filter(e -> e.body().contains(group.getGroupCode())).findFirst().orElseThrow();
        assertThat(email.to()).containsExactly(leader.getEmail());
        assertThat(email.cc()).contains(member.getEmail(), supervisor.getEmail());
        assertThat(email.body()).contains("Bổ sung biểu đồ");
        putAs(feedbackUrl, supervisor, Map.of("feedback", "Đạt", "accepted", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        postJson(url, leader, document).andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        putAs(feedbackUrl, supervisor, Map.of("feedback", "Old", "accepted", true)).andExpect(status().isBadRequest());
    }

    @Test
    void overdueTopicCannotBeApprovedButCanBeClosedForResubmission() throws Exception {
        User supervisor = user("deadline-supervisor", Role.INSTRUCTOR);
        User council = user("deadline-council", Role.COUNCIL);
        User leader = user("deadline-leader", Role.GROUP_LEADER);
        StudentGroup group = group("DEADLINE", supervisor, false);
        join(group, leader, true);
        var topics = IntStream.range(0, 10).mapToObj(i -> Map.of("title", "Topic " + i)).toList();
        JsonNode submitted = body(postJson("/api/v1/groups/" + group.getId() + "/topic-proposals", leader, Map.of("topics", topics))
                .andExpect(status().isCreated()));
        String id = submitted.get("id").asText();
        postJson("/api/v1/topic-proposals/" + id + "/forward", supervisor,
                Map.of("itemId", submitted.get("topics").get(0).get("id").asText())).andExpect(status().isOk());
        var proposal = proposals.findById(UUID.fromString(id)).orElseThrow();
        proposal.setCouncilDeadline(Instant.now().minusSeconds(1));
        proposals.save(proposal);
        postJson("/api/v1/topic-proposals/" + id + "/decision", council, Map.of("decision", "APPROVED"))
                .andExpect(status().isConflict());
        postJson("/api/v1/topic-proposals/" + id + "/decision", council,
                Map.of("decision", "REJECTED", "feedback", "Quá hạn; nộp lại đợt tiếp theo"))
                .andExpect(status().isOk());
        assertThat(studentGroupRepository.findById(group.getId()).orElseThrow().getTopic()).isNull();
    }

    @Test
    void reviewWeeksRequireCalendarAndCloneCannotEscapeWeekSeven() throws Exception {
        User admin = user("calendar-admin", Role.ADMIN);
        User lecturer = user("calendar-lecturer", Role.INSTRUCTOR);
        StudentGroup group = group("CALENDAR", lecturer, true);
        LocalDate start = LocalDate.of(2035, 1, 1);
        Instant week3 = start.plusDays(14).atTime(9, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        Map<String,Object> review = review(group, lecturer, "REVIEW_1", week3);
        putAs("/api/v1/semesters/" + "x".repeat(21), admin, Map.of("startDate", start.toString()))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/reviews", admin, review).andExpect(status().isBadRequest());
        putAs("/api/v1/semesters/" + group.getSemester(), lecturer, Map.of("startDate", start.toString()))
                .andExpect(status().isForbidden());
        putAs("/api/v1/semesters/" + group.getSemester(), admin, Map.of("startDate", start.toString())).andExpect(status().isOk());
        review.put("scheduledAt", week3.minusSeconds(86400).toString());
        postJson("/api/v1/reviews", admin, review).andExpect(status().isBadRequest());
        review.put("scheduledAt", week3.toString());
        postJson("/api/v1/reviews", admin, review).andExpect(status().isCreated());
        putAs("/api/v1/semesters/" + group.getSemester(), admin, Map.of("startDate", start.plusDays(1).toString()))
                .andExpect(status().isConflict());
        postJson("/api/v1/reviews/clone", admin, Map.of("semester", group.getSemester(), "fromRound", "REVIEW_1",
                "toRound", "REVIEW_2", "offsetDays", 21)).andExpect(status().isBadRequest());
        assertThat(reviews.existsByGroupIdAndRound(group.getId(), ReviewRound.REVIEW_2)).isFalse();
        postJson("/api/v1/reviews/clone", admin, Map.of("semester", group.getSemester(), "fromRound", "REVIEW_1",
                "toRound", "REVIEW_2", "offsetDays", 28)).andExpect(status().isOk());
    }

    @Test
    void sameGroupCannotRaceBookingsAcrossDifferentSlotsAndOutsidersCannotCancel() throws Exception {
        User supervisor = user("race-supervisor", Role.INSTRUCTOR);
        User leader = user("race-leader", Role.GROUP_LEADER);
        StudentGroup group = group("RACE", supervisor, true);
        join(group, leader, true);
        Instant at = Instant.now().plus(Duration.ofDays(20));
        String first = slot(supervisor, at);
        String second = slot(supervisor, at.plus(Duration.ofDays(1)));
        var results = race(
                () -> postJson("/api/v1/slots/" + first + "/book", leader, Map.of("groupId", group.getId())).andReturn(),
                () -> postJson("/api/v1/slots/" + second + "/book", leader, Map.of("groupId", group.getId())).andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200, 400);
        var successful = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        String bookingId = objectMapper.readTree(successful.getResponse().getContentAsString()).get("id").asText();
        for (User outsider : List.of(user("race-outsider", Role.GROUP_LEADER), user("race-other-gv", Role.INSTRUCTOR))) {
            mockMvc.perform(delete("/api/v1/bookings/" + bookingId).header("Authorization", bearer(outsider))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(delete("/api/v1/bookings/" + bookingId).header("Authorization", bearer(leader))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void fortyGroupsCanDefendInARollingSchedule() throws Exception {
        User admin = user("forty-admin", Role.ADMIN);
        User chair = user("forty-chair", Role.COUNCIL);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            StudentGroup g = group("FORTY-" + i, admin, true);
            readyForDefense(g);
            ids.add(g.getId());
        }
        Instant first = Instant.now().plus(Duration.ofDays(1000));
        JsonNode sessions = body(postJson("/api/v1/defenses/rolling", admin, Map.of("attempt", 1, "groupIds", ids,
                "firstStartTime", first.toString(), "durationMinutes", 15, "breakMinutes", 0,
                "room", "FORTY-" + suffix, "committeeIds", List.of(chair.getId()), "chairId", chair.getId()))
                .andExpect(status().isCreated()));
        assertThat(sessions).hasSize(40);
        for (int i = 0; i < 40; i++) {
            assertThat(Instant.parse(sessions.get(i).get("scheduledAt").asText())).isEqualTo(first.plusSeconds(900L * i));
        }
    }

    @Test
    void simultaneousDefenseRequestsCannotTakeTheSameRoom() throws Exception {
        User admin = user("room-admin", Role.ADMIN);
        User chair = user("room-chair", Role.COUNCIL);
        StudentGroup a = group("ROOM-A", admin, true);
        StudentGroup b = group("ROOM-B", admin, true);
        readyForDefense(a);
        readyForDefense(b);
        Instant at = Instant.now().plus(Duration.ofDays(2000));
        var results = race(
                () -> postJson("/api/v1/defenses", admin, defense(a, chair, at)).andReturn(),
                () -> postJson("/api/v1/defenses", admin, defense(b, chair, at)).andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void lateRevisionConfirmationDoesNotReturnGroupToDefenseOne() throws Exception {
        User admin = user("late-admin", Role.ADMIN);
        StudentGroup g = group("LATE", admin, true);
        ReviewSession council = reviews.save(ReviewSession.builder().group(g).round(ReviewRound.REVIEW_3)
                .scheduledAt(Instant.now().minus(Duration.ofDays(8))).durationMinutes(45).location("Late")
                .outcome(ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1).completedAt(Instant.now().minus(Duration.ofDays(7)))
                .revisionDeadline(Instant.now().minusSeconds(1)).build());
        postJson("/api/v1/reviews/" + council.getId() + "/revision-complete", admin, Map.of()).andExpect(status().isConflict());
        getAs("/api/v1/groups/" + g.getId() + "/overview", admin).andExpect(jsonPath("$.defenseTrack").value("DEFENSE_2"));
    }

    @Test
    void parallelLimitCountsConcurrentSessionsRatherThanAllIntersectingSessions() throws Exception {
        User admin = user("parallel-admin", Role.ADMIN);
        Instant at = Instant.now().plus(Duration.ofDays(3000));
        for (int i = 0; i < 5; i++) {
            StudentGroup g = group("PARALLEL-" + i, admin, true);
            readyForDefense(g);
            User chair = user("parallel-chair-" + i, Role.COUNCIL);
            Map<String,Object> request = new HashMap<>(defense(g, chair, at.plusSeconds(1800L * i)));
            request.put("room", "parallel-" + i + "-" + suffix);
            postJson("/api/v1/defenses", admin, request).andExpect(status().isCreated());
        }
        // Intersects five sequential sessions, but only two run at any one instant.
        StudentGroup longGroup = group("PARALLEL-LONG", admin, true);
        readyForDefense(longGroup);
        User chair = user("parallel-long-chair", Role.COUNCIL);
        Map<String,Object> longRequest = new HashMap<>(defense(longGroup, chair, at));
        longRequest.put("durationMinutes", 150);
        longRequest.put("room", "parallel-long-" + suffix);
        postJson("/api/v1/defenses", admin, longRequest).andExpect(status().isCreated());
        // A separate window with five simultaneous sessions really is full.
        Instant busy = at.plus(Duration.ofDays(1));
        for (int i = 0; i < 6; i++) {
            StudentGroup g = group("BUSY-" + i, admin, true);
            readyForDefense(g);
            Map<String,Object> request = new HashMap<>(defense(g, user("busy-chair-" + i, Role.COUNCIL), busy));
            request.put("room", "busy-" + i + "-" + suffix);
            postJson("/api/v1/defenses", admin, request).andExpect(i < 5 ? status().isCreated() : status().isConflict());
        }
    }

    private void readyForDefense(StudentGroup g) {
        reviews.save(ReviewSession.builder().group(g).round(ReviewRound.REVIEW_3)
                .scheduledAt(Instant.now().minus(Duration.ofDays(1))).durationMinutes(45).location("completed-" + g.getId())
                .completedAt(Instant.now()).outcome(ClosedCouncilOutcome.READY_FOR_DEFENSE_1).build());
    }

    private Map<String,Object> defense(StudentGroup g, User chair, Instant at) {
        return Map.of("groupId", g.getId(), "attempt", 1, "scheduledAt", at.toString(), "durationMinutes", 30,
                "room", "RACE-ROOM-" + suffix, "committeeIds", List.of(chair.getId()), "chairId", chair.getId());
    }

    private Map<String,Object> review(StudentGroup g, User lecturer, String round, Instant at) {
        return new HashMap<>(Map.of("groupId", g.getId(), "round", round, "scheduledAt", at.toString(), "durationMinutes", 45,
                "location", "CAL-" + suffix, "reviewerIds", List.of(lecturer.getId())));
    }

    private String slot(User instructor, Instant at) throws Exception {
        return body(postJson("/api/v1/slots", instructor, Map.of("startTime", at.toString(), "endTime", at.plusSeconds(1800).toString(),
                "durationMinutes", 30, "capacity", 1, "locationType", "ONLINE", "meetingUrl", "https://meet.example.com"))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions putAs(String url, User user, Object data) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(data)));
    }

    private List<org.springframework.test.web.servlet.MvcResult> race(
            Callable<org.springframework.test.web.servlet.MvcResult> a, Callable<org.springframework.test.web.servlet.MvcResult> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<org.springframework.test.web.servlet.MvcResult>> futures = new ArrayList<>();
            for (var action : List.of(a, b)) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race did not start");
                return action.call();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
