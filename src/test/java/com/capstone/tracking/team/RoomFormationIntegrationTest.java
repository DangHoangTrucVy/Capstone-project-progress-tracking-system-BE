package com.capstone.tracking.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.notification.NotificationRepository;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterPhase;
import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Requirement v3.0: rooms, the Leader, finalize requests, the deadline, round 1, the supplement phase and round 2. */
class RoomFormationIntegrationTest extends TestSupport {

    @Autowired private NotificationRepository notifications;

    @Test
    void theFirstStudentInAnEmptyRoomIsItsLeaderAndOthersWalkInUpToTheMaximum() throws Exception {
        World w = world(7, 1);
        List<User> s = w.students();
        UUID room = w.rooms().get(0);
        JsonNode first = joinRoom(room, s.get(0));
        assertThat(first.get("joined").asBoolean()).isTrue();
        assertThat(first.get("leader").asBoolean()).isTrue();
        assertThat(first.get("room").get("status").asText()).isEqualTo("OPEN");
        assertThat(joinRoom(room, s.get(1)).get("leader").asBoolean()).isFalse();
        // One room per student; rooms of one major only.
        postJson("/api/v1/rooms/" + w.rooms().get(1) + "/join", s.get(1), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + room + "/join", w.others().get(0), Map.of()).andExpect(status().isConflict());
        for (int i = 2; i < 5; i++) {
            joinRoom(room, s.get(i));
        }
        getAs("/api/v1/rooms/" + room, s.get(0)).andExpect(jsonPath("$.status").value("FULL"))
                .andExpect(jsonPath("$.code").doesNotExist());
        postJson("/api/v1/rooms/" + room + "/join", s.get(5), Map.of()).andExpect(status().isConflict());
        // The Moderator may raise the maximum once formation started, never lower it.
        putJson("/api/v1/semesters/" + w.code() + "/rules", w.moderator(), Map.of("maxGroupSize", 6))
                .andExpect(status().isOk());
        joinRoom(room, s.get(5));
        // A student sees the rooms of their own major only.
        JsonNode mine = body(getAs("/api/v1/semesters/" + w.code() + "/rooms", s.get(6)).andExpect(status().isOk()));
        assertThat(mine.get("totalElements").asInt()).isEqualTo(w.rooms().size());
        getAs("/api/v1/semesters/" + w.code() + "/rooms/search?available=true", s.get(6)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(w.rooms().size() - 1));
    }

    @Test
    void aLockedRoomTakesRequestsTheLeaderAcceptsWithinTheSemesterLimit() throws Exception {
        World w = world(12, 0);
        List<User> s = w.students();
        UUID a = createGroup(w, s.get(0), "A");
        UUID b = createGroup(w, s.get(1), "B");
        UUID c = createGroup(w, s.get(2), "C");
        UUID request = requestToJoin(a, s.get(0), s.get(5));
        requestToJoin(b, s.get(1), s.get(5));
        requestToJoin(c, s.get(2), s.get(5));
        // A fourth room would exceed the 3 pending requests of the rules.
        UUID d = createGroup(w, s.get(3), "D");
        postJson("/api/v1/rooms/" + d + "/lock", s.get(3), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + d + "/join", s.get(5), Map.of()).andExpect(status().isConflict());
        // Only the Leader accepts; the student joins and the other requests are cancelled.
        postJson("/api/v1/requests/" + request + "/accept", s.get(5), Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/requests/" + request + "/accept", s.get(0), Map.of()).andExpect(status().isOk());
        assertThat(memberRepository.countActive(a)).isEqualTo(2);
        JsonNode mine = body(getAs("/api/v1/semesters/" + w.code() + "/my-requests", s.get(5)));
        assertThat(mine.toString()).contains("ACCEPTED", "CANCELLED").doesNotContain("PENDING");
        // Unlocking lets students walk in again, so waiting requests are cancelled.
        UUID waiting = requestToJoin(b, s.get(1), s.get(6));
        postJson("/api/v1/rooms/" + b + "/unlock", s.get(1), Map.of()).andExpect(status().isOk());
        getAs("/api/v1/requests/" + waiting, s.get(6)).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void leavingReplacesTheLeaderAndTheLastOneOutLeavesTheRoomEmpty() throws Exception {
        World w = world(4, 0);
        List<User> s = w.students();
        UUID room = createGroup(w, s.get(0), "Leavers");
        join(room, s.get(0), s.get(1));
        join(room, s.get(0), s.get(2));
        postJson("/api/v1/rooms/" + room + "/leave", s.get(0), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.left").value(true));
        getAs("/api/v1/rooms/" + room, s.get(1)).andExpect(jsonPath("$.leaderId").value(s.get(1).getId().toString()));
        // A locked room: a member asks the Leader; the Leader must transfer or unlock first.
        postJson("/api/v1/rooms/" + room + "/lock", s.get(1), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + room + "/leave", s.get(2), Map.of("reason", "x")).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("PENDING_LEADER"));
        postJson("/api/v1/rooms/" + room + "/leave", s.get(1), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + room + "/unlock", s.get(1), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + room + "/leave", s.get(1), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + room + "/leave", s.get(2), Map.of()).andExpect(status().isOk());
        StudentGroup empty = groupRepository.findById(room).orElseThrow();
        assertThat(empty.isDissolved()).isFalse();
        assertThat(memberRepository.countActive(room)).isZero();
        getAs("/api/v1/rooms/" + room, s.get(3)).andExpect(jsonPath("$.status").value("EMPTY"));
        // Somebody else walks in and leads it.
        assertThat(joinRoom(room, s.get(3)).get("leader").asBoolean()).isTrue();
    }

    @Test
    void theModeratorApprovesFinalizeRequestsAndIssuesCodesInApprovalOrder() throws Exception {
        World w = world(9, 0);
        List<User> s = w.students();
        UUID a = createGroup(w, s.get(0), "Alpha");
        join(a, s.get(0), s.get(1));
        postJson("/api/v1/rooms/" + a + "/finalize-request", s.get(0), Map.of()).andExpect(status().isConflict());
        join(a, s.get(0), s.get(2));
        postJson("/api/v1/rooms/" + a + "/finalize-request", s.get(1), Map.of()).andExpect(status().isForbidden());
        postJson("/api/v1/rooms/" + a + "/finalize-request", s.get(0), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZE_REQUESTED"));
        // While waiting, nobody walks in or out.
        postJson("/api/v1/rooms/" + a + "/join", s.get(3), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + a + "/leave", s.get(2), Map.of()).andExpect(status().isConflict());
        getAs("/api/v1/semesters/" + w.code() + "/finalize-requests", w.moderator())
                .andExpect(jsonPath("$.length()").value(1));
        postJson("/api/v1/rooms/" + a + "/finalize-decision", w.moderator(), Map.of("approve", false, "note", "Thiếu BA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalizeStatus").value("RETURNED"));

        UUID b = createGroup(w, s.get(4), "Beta");
        join(b, s.get(4), s.get(5));
        join(b, s.get(4), s.get(6));
        postJson("/api/v1/rooms/" + b + "/finalize-request", s.get(4), Map.of()).andExpect(status().isOk());
        String prefix = w.code() + "-" + w.major().getCode() + "-";
        postJson("/api/v1/rooms/" + b + "/finalize-decision", w.moderator(), Map.of("approve", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(prefix + "001"))
                .andExpect(jsonPath("$.status").value("FINALIZED"));
        postJson("/api/v1/rooms/" + a + "/finalize-request", s.get(0), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + a + "/finalize-decision", w.moderator(), Map.of("approve", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(prefix + "002"));
        // An approved group: students neither join, leave by themselves nor get kicked by the Leader.
        postJson("/api/v1/rooms/" + b + "/join", s.get(7), Map.of()).andExpect(status().isConflict());
        deleteAs("/api/v1/rooms/" + b + "/members/" + s.get(6).getId(), s.get(4)).andExpect(status().isConflict());
        postJson("/api/v1/rooms/" + b + "/leave", s.get(6), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("PENDING_MODERATOR"));
        // The Moderator still takes a member out, with a reason.
        postJson("/api/v1/rooms/" + b + "/moderation/remove", w.moderator(), Map.of("userId", s.get(5).getId()))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/rooms/" + b + "/moderation/remove", w.moderator(),
                Map.of("userId", s.get(5).getId(), "reason", "Theo quy định")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.code").value(prefix + "001"));
    }

    @Test
    void theDeadlineLocksRoomsAndRound1WaitsForSmallRoomsAndComplaints() throws Exception {
        World w = world(8, 0);
        List<User> s = w.students();
        UUID full = createGroup(w, s.get(0), "Full");
        join(full, s.get(0), s.get(1));
        join(full, s.get(0), s.get(2));
        UUID small = createGroup(w, s.get(3), "Small");
        join(small, s.get(3), s.get(4));
        postJson("/api/v1/rooms/" + small + "/lock", s.get(3), Map.of()).andExpect(status().isOk());
        UUID pending = requestToJoin(small, s.get(3), s.get(5));
        postJson("/api/v1/rooms/" + small + "/leave", s.get(4), Map.of("reason", "x")).andExpect(status().isOk());

        // Round 1 cannot happen before the deadline.
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of())
                .andExpect(status().isConflict());
        Semester locked = passDeadline(w);
        assertThat(locked.getRoomsLockedAt()).isNotNull();
        getAs("/api/v1/requests/" + pending, s.get(5)).andExpect(jsonPath("$.status").value("CANCELLED"));
        getAs("/api/v1/rooms/" + full, s.get(0)).andExpect(jsonPath("$.status").value("DEADLINE_LOCKED"));
        postJson("/api/v1/rooms/" + w.rooms().get(2) + "/join", s.get(6), Map.of()).andExpect(status().isConflict());
        // The leave request became a complaint; the small room is not disbanded automatically.
        JsonNode complaints = body(getAs("/api/v1/semesters/" + w.code() + "/complaints", w.moderator()));
        assertThat(complaints.size()).isEqualTo(1);
        assertThat(memberRepository.countActive(small)).isEqualTo(2);
        JsonNode check = body(getAs("/api/v1/semesters/" + w.code() + "/phase-check", w.moderator()));
        assertThat(check.get("canFinalizeRound1").asBoolean()).isFalse();
        assertThat(check.get("roomsBelowMinimum").size()).isEqualTo(1);
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of())
                .andExpect(status().isConflict());

        postJson("/api/v1/leave-requests/" + complaints.get(0).get("id").asText() + "/moderator-decision", w.moderator(),
                Map.of("approve", false)).andExpect(status().isOk());
        getAs("/api/v1/semesters/" + w.code() + "/rooms?belowMinimum=true", w.moderator())
                .andExpect(jsonPath("$.totalElements").value(1));
        postJson("/api/v1/semesters/" + w.code() + "/rooms/disband", w.moderator(), Map.of("allBelowMinimum", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rooms").value(1)).andExpect(jsonPath("$.students").value(2));
        assertThat(groupRepository.findById(small).orElseThrow().isDissolved()).isFalse();
        // The Moderator places the leftovers into rooms not finalized, up to the maximum.
        postJson("/api/v1/rooms/" + full + "/moderation/add", w.moderator(), Map.of("userId", s.get(3).getId()))
                .andExpect(status().isOk());
        postJson("/api/v1/rooms/" + full + "/moderation/add", w.moderator(), Map.of("userId", s.get(4).getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(5));
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("SUPPLEMENT"));
    }

    @Test
    void theSupplementPhaseRunsOnInvitesAndRound2TurnsRoomsIntoCodedGroups() throws Exception {
        World w = world(7, 0);
        List<User> s = w.students();
        UUID a = createGroup(w, s.get(0), "A");
        join(a, s.get(0), s.get(1));
        join(a, s.get(0), s.get(2));
        UUID b = createGroup(w, s.get(3), "B");
        join(b, s.get(3), s.get(4));
        join(b, s.get(3), s.get(5));
        postJson("/api/v1/rooms/" + a + "/finalize-request", s.get(0), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/rooms/" + a + "/finalize-decision", w.moderator(), Map.of("approve", true)).andExpect(status().isOk());
        passDeadline(w);
        User lone = s.get(6);
        // Leftovers block skipping the supplement phase.
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of("skipSupplement", true))
                .andExpect(status().isConflict());
        long leaderBefore = notifications.countByRecipientIdAndReadAtIsNull(s.get(0).getId());
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of()).andExpect(status().isOk());
        // The rooms with seats hear that a student needs a group.
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(s.get(0).getId())).isGreaterThan(leaderBefore);
        getAs("/api/v1/me/semesters", lone).andExpect(jsonPath("$[0].phase").value("SUPPLEMENT"))
                .andExpect(jsonPath("$[0].canAcceptInvites").value(true));
        // Only Leaders invite; invites do not expire; several rooms may invite the same student.
        JsonNode inviteA = body(postJson("/api/v1/rooms/" + a + "/invitations", s.get(0), Map.of("studentId", lone.getId()))
                .andExpect(status().isCreated()));
        assertThat(inviteA.has("expiresAt")).isFalse();
        UUID inviteB = id(postJson("/api/v1/rooms/" + b + "/invitations", s.get(3), Map.of("studentId", lone.getId()))
                .andExpect(status().isCreated()));
        postJson("/api/v1/rooms/" + b + "/invitations", s.get(4), Map.of("studentId", lone.getId()))
                .andExpect(status().isForbidden());
        long moderatorBefore = notifications.countByRecipientIdAndReadAtIsNull(w.moderator().getId());
        postJson("/api/v1/requests/" + inviteB + "/accept", lone, Map.of()).andExpect(status().isOk());
        assertThat(notifications.countByRecipientIdAndReadAtIsNull(w.moderator().getId())).isGreaterThan(moderatorBefore);
        getAs("/api/v1/requests/" + inviteA.get("id").asText(), lone).andExpect(jsonPath("$.status").value("CANCELLED"));

        postJson("/api/v1/semesters/" + w.code() + "/finalize-round2", w.moderator(), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("CLOSED"));
        String prefix = w.code() + "-" + w.major().getCode() + "-";
        assertThat(groupRepository.findById(a).orElseThrow().getCode()).isEqualTo(prefix + "001");
        assertThat(groupRepository.findById(b).orElseThrow().getCode()).isEqualTo(prefix + "002");
        assertThat(groupRepository.findById(b).orElseThrow().isFinalized()).isTrue();
        // Empty rooms were closed and are no longer listed.
        getAs("/api/v1/semesters/" + w.code() + "/rooms", w.moderator()).andExpect(jsonPath("$.totalElements").value(2));
        // View and export only; never reopened.
        postJson("/api/v1/rooms/" + b + "/moderation/remove", w.moderator(), Map.of("userId", lone.getId(), "reason", "x"))
                .andExpect(status().isConflict());
        putJson("/api/v1/semesters/" + w.code() + "/deadline", w.moderator(),
                Map.of("deadline", Instant.now().plus(Duration.ofDays(3)).toString())).andExpect(status().isConflict());
        getAs("/api/v1/semesters/" + w.code() + "/export", w.moderator()).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        getAs("/api/v1/semesters/" + w.code() + "/statistics", w.moderator()).andExpect(status().isOk())
                .andExpect(jsonPath("$.total.groups").value(2)).andExpect(jsonPath("$.total.withoutGroup").value(0));
    }

    @Test
    void withNobodyLeftRound1SkipsTheSupplementPhase() throws Exception {
        World w = world(3, 0);
        List<User> s = w.students();
        UUID a = createGroup(w, s.get(0), "Everyone");
        join(a, s.get(0), s.get(1));
        join(a, s.get(0), s.get(2));
        passDeadline(w);
        postJson("/api/v1/semesters/" + w.code() + "/finalize-round1", w.moderator(), Map.of("skipSupplement", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("CLOSED"));
        assertThat(groupRepository.findById(a).orElseThrow().getCode()).isEqualTo(w.code() + "-" + w.major().getCode() + "-001");
        assertThat(semesterRepository.findById(w.code()).orElseThrow().getPhase()).isEqualTo(SemesterPhase.CLOSED);
    }

    @Test
    void anExtensionBeforeRound1ReopensTheRooms() throws Exception {
        World w = world(3, 0);
        passDeadline(w);
        postJson("/api/v1/rooms/" + w.rooms().get(0) + "/join", w.students().get(0), Map.of()).andExpect(status().isConflict());
        putJson("/api/v1/semesters/" + w.code() + "/deadline", w.moderator(),
                Map.of("deadline", Instant.now().plus(Duration.ofDays(2)).toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.beforeDeadline").value(true));
        assertThat(joinRoom(w.rooms().get(0), w.students().get(0)).get("joined").asBoolean()).isTrue();
    }

    @Test
    void roomsAreCreatedInBulkByMajorAndTheirMinimumBelongsToEachRoom() throws Exception {
        World w = openingWorld(10, 0);
        JsonNode plan = body(getAs("/api/v1/semesters/" + w.code() + "/rooms/plan", w.moderator()).andExpect(status().isOk()));
        assertThat(plan.get(0).get("suggestedRooms").asInt()).isEqualTo(4);
        assertThat(plan.get(0).get("rooms").asInt()).isEqualTo(w.rooms().size());
        JsonNode more = body(postJson("/api/v1/semesters/" + w.code() + "/rooms", w.moderator(),
                Map.of("majorId", w.major().getId(), "count", 2, "minSize", 4)).andExpect(status().isCreated()));
        assertThat(more.get(0).get("name").asText()).isEqualTo(String.format("Room %02d", w.rooms().size() + 1));
        assertThat(more.get(0).get("minSize").asInt()).isEqualTo(4);
        UUID room = w.rooms().get(0);
        putJson("/api/v1/rooms/" + room, w.moderator(), Map.of("minSize", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minSize").value(2));
        putJson("/api/v1/rooms/" + room, w.moderator(), Map.of("minSize", 9)).andExpect(status().isBadRequest());
        putJson("/api/v1/rooms/" + room, w.students().get(0), Map.of("minSize", 2)).andExpect(status().isConflict());
        postJson("/api/v1/semesters/" + w.code() + "/rooms", w.students().get(0),
                Map.of("majorId", w.major().getId(), "count", 1)).andExpect(status().isForbidden());
    }
}
